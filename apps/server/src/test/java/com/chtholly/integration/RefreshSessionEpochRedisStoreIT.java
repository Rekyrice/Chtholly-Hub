package com.chtholly.integration;

import com.chtholly.admin.service.AdminAuditService;
import com.chtholly.admin.service.AdminUserService;
import com.chtholly.admin.security.UserBanService;
import com.chtholly.auth.api.dto.AuthResponse;
import com.chtholly.auth.api.dto.RegisterRequest;
import com.chtholly.auth.audit.LoginLogService;
import com.chtholly.auth.config.AuthProperties;
import com.chtholly.auth.event.UserRegisteredEvent;
import com.chtholly.auth.model.ClientInfo;
import com.chtholly.auth.model.IdentifierType;
import com.chtholly.auth.service.AuthIdentityPolicy;
import com.chtholly.auth.service.AuthRegistrationService;
import com.chtholly.auth.service.AuthRegistrationSideEffectCoordinator;
import com.chtholly.auth.service.AuthTokenLifecycleService;
import com.chtholly.auth.token.JwtService;
import com.chtholly.auth.token.RedisRefreshTokenStore;
import com.chtholly.auth.token.RefreshSessionEpochAuthority;
import com.chtholly.auth.token.TokenPair;
import com.chtholly.auth.verification.RedisVerificationCodeStore;
import com.chtholly.auth.verification.VerificationCheckResult;
import com.chtholly.auth.verification.VerificationCodeStatus;
import com.chtholly.auth.verification.VerificationCodeStore;
import com.chtholly.auth.verification.VerificationScene;
import com.chtholly.auth.verification.VerificationService;
import com.chtholly.config.SiteProperties;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.chtholly.user.service.impl.UserServiceImpl;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies refresh-token fencing across real MySQL and Redis boundaries.
 *
 * <p>The application services and transaction advice are real. Only the
 * unrelated audit writer is replaced so a rollback assertion remains focused
 * on the user row and its refresh-session epoch.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RefreshSessionEpochRedisStoreIT {

    private static final long USER_ID = 7L;
    private static final long ADMIN_ID = 8L;
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("refresh_epoch_store")
                    .withUsername("refresh_epoch_store")
                    .withPassword("refresh_epoch_store");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static MysqlDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static GatedStringRedisTemplate gatedRedis;
    private static AnnotationConfigApplicationContext springContext;
    private static RefreshSessionEpochAuthority epochAuthority;
    private static RedisRefreshTokenStore store;
    private static RedisRefreshTokenStore gatedStore;
    private static AdminUserService adminUserService;
    private static AuthRegistrationService registrationService;
    private static RedisVerificationCodeStore verificationCodeStore;
    private static JwtService jwtService;
    private static VerificationService verificationService;
    private static PasswordEncoder passwordEncoder;
    private static LoginLogService loginLogService;
    private static RegistrationEventRecorder registrationEvents;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void connectInfrastructure() throws Exception {
        dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource("db/schema.sql"));
        }

        RedisStandaloneConfiguration redisConfiguration =
                new RedisStandaloneConfiguration(
                        REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(redisConfiguration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        gatedRedis = new GatedStringRedisTemplate(connectionFactory);

        SqlSessionFactory mapperFactory = mapperFactory();
        SqlSessionTemplate sqlSessionTemplate =
                new SqlSessionTemplate(mapperFactory);
        springContext = new AnnotationConfigApplicationContext();
        springContext.register(TransactionConfiguration.class);
        springContext.registerBean(DataSource.class, () -> dataSource);
        springContext.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        springContext.registerBean(
                UserMapper.class,
                () -> sqlSessionTemplate.getMapper(UserMapper.class));
        springContext.registerBean(StringRedisTemplate.class, () -> redis);
        springContext.registerBean(RefreshSessionEpochAuthority.class);
        springContext.registerBean(RedisRefreshTokenStore.class);
        springContext.registerBean(RedisVerificationCodeStore.class);
        springContext.registerBean(UserServiceImpl.class);
        jwtService = mock(JwtService.class);
        verificationService = mock(VerificationService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        loginLogService = mock(LoginLogService.class);
        springContext.registerBean(
                JwtService.class,
                () -> jwtService);
        springContext.registerBean(
                UserBanService.class,
                () -> mock(UserBanService.class));
        springContext.registerBean(AuthTokenLifecycleService.class);
        springContext.registerBean(AuthProperties.class);
        springContext.registerBean(AuthIdentityPolicy.class);
        springContext.registerBean(
                VerificationService.class,
                () -> verificationService);
        springContext.registerBean(
                PasswordEncoder.class,
                () -> passwordEncoder);
        springContext.registerBean(
                LoginLogService.class,
                () -> loginLogService);
        springContext.registerBean(RegistrationEventRecorder.class);
        springContext.registerBean(AuthRegistrationSideEffectCoordinator.class);
        springContext.registerBean(AuthRegistrationService.class);
        springContext.registerBean(
                AdminAuditService.class,
                () -> mock(AdminAuditService.class));
        springContext.registerBean(
                SiteProperties.class,
                () -> new SiteProperties(
                        999L,
                        888888888888888888L,
                        "",
                        "owner",
                        "Owner"));
        springContext.registerBean(AdminUserService.class);
        springContext.refresh();

        epochAuthority = springContext.getBean(
                RefreshSessionEpochAuthority.class);
        store = springContext.getBean(RedisRefreshTokenStore.class);
        gatedStore = new RedisRefreshTokenStore(gatedRedis, epochAuthority);
        adminUserService = springContext.getBean(AdminUserService.class);
        registrationService = springContext.getBean(
                AuthRegistrationService.class);
        verificationCodeStore = springContext.getBean(
                RedisVerificationCodeStore.class);
        registrationEvents = springContext.getBean(
                RegistrationEventRecorder.class);
        transactions = new TransactionTemplate(springContext.getBean(
                PlatformTransactionManager.class));
    }

    @AfterAll
    static void disconnectInfrastructure() {
        if (springContext != null) {
            springContext.close();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void resetInfrastructure() {
        reset(
                jwtService,
                verificationService,
                passwordEncoder,
                loginLogService);
        registrationEvents.clear();
        gatedRedis.clearGate();
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", USER_ID, ADMIN_ID);
        jdbc.update("""
                INSERT INTO users
                    (id, email, password_hash, nickname, handle, role)
                VALUES
                    (?, 'member@example.com', 'member-hash',
                        'Member', 'member', 'USER'),
                    (?, 'admin@example.com', 'admin-hash',
                        'Admin', 'admin', 'ADMIN')
                """, USER_ID, ADMIN_ID);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(jwtService.issueTokenPair(any(User.class)))
                .thenReturn(tokenPair("registration-jti"));
    }

    @Test
    void tokenIssuanceFailureRollsBackTheNewUser() {
        doThrow(new IllegalStateException("token issuance failed"))
                .when(jwtService)
                .issueTokenPair(any(User.class));

        assertThatThrownBy(() -> registrationService.register(
                handleRegistration("rollback_member"),
                clientInfo()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("token issuance failed");

        assertThat(countUsersByHandle("rollback_member")).isZero();
        verify(loginLogService, never()).recordSuccess(
                any(), anyString(), anyString(), anyString(), anyString());
        assertThat(registrationEvents.snapshot()).isEmpty();
    }

    @Test
    void registrationPublishesAuditAndEventOnlyAfterCommit() {
        AtomicReference<AuthResponse> registered = new AtomicReference<>();

        transactions.executeWithoutResult(ignored -> {
            AuthResponse response = registrationService.register(
                    handleRegistration("committed_member"),
                    clientInfo());
            registered.set(response);

            assertThat(countUsersByHandle("committed_member")).isEqualTo(1);
            assertThat(redis.opsForValue().get(tokenKey(
                    response.user().id(), "registration-jti")))
                    .isEqualTo("mysql:1");
            verify(loginLogService, never()).recordSuccess(
                    any(), anyString(), anyString(), anyString(), anyString());
            assertThat(registrationEvents.snapshot()).isEmpty();
        });

        AuthResponse response = registered.get();
        assertThat(response).isNotNull();
        assertThat(countUsersByHandle("committed_member")).isEqualTo(1);
        assertThat(store.isTokenValid(
                response.user().id(), "registration-jti")).isTrue();
        verify(loginLogService).recordSuccess(
                response.user().id(),
                "committed_member",
                "REGISTER",
                "127.0.0.1",
                "integration-test");
        assertThat(registrationEvents.snapshot())
                .extracting(event -> event.user().getId())
                .containsExactly(response.user().id());
    }

    @Test
    void rollbackAfterRedisBootstrapDeletesThePendingMembership() {
        AtomicLong pendingUserId = new AtomicLong();

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            AuthResponse response = registrationService.register(
                    handleRegistration("outer_rollback_member"),
                    clientInfo());
            pendingUserId.set(response.user().id());
            assertThat(redis.opsForValue().get(tokenKey(
                    response.user().id(), "registration-jti")))
                    .isEqualTo("mysql:1");
            throw new ForcedRegistrationRollback();
        })).isInstanceOf(ForcedRegistrationRollback.class);

        assertThat(pendingUserId.get()).isPositive();
        assertThat(countUsersByHandle("outer_rollback_member")).isZero();
        assertThat(redis.hasKey(tokenKey(
                pendingUserId.get(), "registration-jti"))).isFalse();
        assertThatThrownBy(() -> store.isTokenValid(
                pendingUserId.get(), "registration-jti"))
                .isInstanceOf(IllegalStateException.class);
        verify(loginLogService, never()).recordSuccess(
                any(), anyString(), anyString(), anyString(), anyString());
        assertThat(registrationEvents.snapshot()).isEmpty();
    }

    @Test
    void verifiedRegistrationFailureKeepsTheCodeConsumedAndRollsBackUser() {
        verificationCodeStore.saveCode(
                VerificationScene.REGISTER.name(),
                "verified@example.com",
                new VerificationCodeStore.IssuedCode("123456", "version-1"),
                Duration.ofMinutes(5),
                5);
        when(verificationService.verify(
                VerificationScene.REGISTER,
                "verified@example.com",
                "123456"))
                .thenAnswer(ignored -> verificationCodeStore.verify(
                        VerificationScene.REGISTER.name(),
                        "verified@example.com",
                        "123456"));
        doThrow(new IllegalStateException("token issuance failed"))
                .when(jwtService)
                .issueTokenPair(any(User.class));

        assertThatThrownBy(() -> registrationService.register(
                verifiedRegistration("verified@example.com", "123456"),
                clientInfo()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("token issuance failed");

        assertThat(countUsersByEmail("verified@example.com")).isZero();
        assertThat(verificationCodeStore.verify(
                VerificationScene.REGISTER.name(),
                "verified@example.com",
                "123456").status())
                .isEqualTo(VerificationCodeStatus.NOT_FOUND);
        verify(loginLogService, never()).recordSuccess(
                any(), anyString(), anyString(), anyString(), anyString());
        assertThat(registrationEvents.snapshot()).isEmpty();
    }

    @Test
    void verificationMismatchDoesNotCreateAUserOrIssueTokens() {
        when(verificationService.verify(
                VerificationScene.REGISTER,
                "mismatch@example.com",
                "000000"))
                .thenReturn(new VerificationCheckResult(
                        VerificationCodeStatus.MISMATCH, 1, 5));

        assertThatThrownBy(() -> registrationService.register(
                verifiedRegistration("mismatch@example.com", "000000"),
                clientInfo()))
                .isInstanceOf(com.chtholly.common.exception.BusinessException.class);

        assertThat(countUsersByEmail("mismatch@example.com")).isZero();
        verify(jwtService, never()).issueTokenPair(any(User.class));
        verify(loginLogService, never()).recordSuccess(
                any(), anyString(), anyString(), anyString(), anyString());
        assertThat(registrationEvents.snapshot()).isEmpty();
    }

    @Test
    void realStoreValidatesRotatesAndHonorsTheMysqlEpoch() {
        store.storeToken(USER_ID, "old", TOKEN_TTL);

        assertThat(redis.opsForValue().get(tokenKey("old")))
                .isEqualTo("mysql:1");
        assertThat(store.isTokenValid(USER_ID, "old")).isTrue();
        assertThat(store.rotateToken(
                USER_ID, "old", "replacement", TOKEN_TTL)).isTrue();
        assertThat(store.isTokenValid(USER_ID, "old")).isFalse();
        assertThat(store.isTokenValid(USER_ID, "replacement")).isTrue();

        epochAuthority.advance(USER_ID);

        assertThat(store.isTokenValid(USER_ID, "replacement")).isFalse();
        assertThat(redis.opsForValue().get(tokenKey("replacement")))
                .isEqualTo("mysql:1");
    }

    @Test
    void epochAdvanceDuringStoreCompensatesTheStaleMembership()
            throws Exception {
        long capturedEpoch = store.captureEpoch(USER_ID);
        ScriptGate gate = gatedRedis.pauseOnceAfter(tokenKey("stale-login"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> attempt = executor.submit(() ->
                    gatedStore.storeTokenIfEpochMatches(
                            USER_ID,
                            "stale-login",
                            TOKEN_TTL,
                            capturedEpoch));
            assertThat(gate.awaitReached()).isTrue();

            epochAuthority.advance(USER_ID);
            gate.resume();

            assertThat(attempt.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(redis.hasKey(tokenKey("stale-login"))).isFalse();
            assertThat(store.isTokenValid(USER_ID, "stale-login")).isFalse();
        } finally {
            gate.resume();
            executor.shutdownNow();
        }
    }

    @Test
    void epochAdvanceDuringValidationFailsTheSecondAuthorityRead()
            throws Exception {
        store.storeToken(USER_ID, "current", TOKEN_TTL);
        ScriptGate gate = gatedRedis.pauseOnceAfter(tokenKey("current"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> validation = executor.submit(() ->
                    gatedStore.isTokenValid(USER_ID, "current"));
            assertThat(gate.awaitReached()).isTrue();

            epochAuthority.advance(USER_ID);
            gate.resume();

            assertThat(validation.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(redis.opsForValue().get(tokenKey("current")))
                    .isEqualTo("mysql:1");
            assertThat(store.isTokenValid(USER_ID, "current")).isFalse();
        } finally {
            gate.resume();
            executor.shutdownNow();
        }
    }

    @Test
    void epochAdvanceDuringRotationCompensatesTheReplacement()
            throws Exception {
        store.storeToken(USER_ID, "old", TOKEN_TTL);
        ScriptGate gate = gatedRedis.pauseOnceAfter(
                tokenKey("replacement"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> rotation = executor.submit(() ->
                    gatedStore.rotateToken(
                            USER_ID,
                            "old",
                            "replacement",
                            TOKEN_TTL));
            assertThat(gate.awaitReached()).isTrue();

            epochAuthority.advance(USER_ID);
            gate.resume();

            assertThat(rotation.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(redis.hasKey(tokenKey("old"))).isFalse();
            assertThat(redis.hasKey(tokenKey("replacement"))).isFalse();
        } finally {
            gate.resume();
            executor.shutdownNow();
        }
    }

    @Test
    void adminBanRollbackRestoresBothAccountStateAndEpoch() {
        assertThat(AopUtils.isAopProxy(adminUserService)).isTrue();
        assertThat(AopUtils.isAopProxy(epochAuthority)).isTrue();

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            adminUserService.banUser(ADMIN_ID, USER_ID);
            throw new IllegalStateException("rollback outer transaction");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM users
                WHERE id = ?
                  AND banned_at IS NULL
                  AND refresh_session_epoch = 1
                """, Integer.class, USER_ID)).isEqualTo(1);
    }

    private static SqlSessionFactory mapperFactory() throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "refresh-epoch-store-it",
                new SpringManagedTransactionFactory(),
                dataSource));
        configuration.addMapper(UserMapper.class);
        try (InputStream input = RefreshSessionEpochRedisStoreIT.class
                .getResourceAsStream("/mapper/UserMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(
                    input,
                    configuration,
                    "mapper/UserMapper.xml",
                    configuration.getSqlFragments())
                    .parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static String tokenKey(String tokenId) {
        return tokenKey(USER_ID, tokenId);
    }

    private static String tokenKey(long userId, String tokenId) {
        return "auth:rt:{%d}:%s".formatted(userId, tokenId);
    }

    private static long countUsersByHandle(String handle) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE handle = ?",
                Long.class,
                handle);
    }

    private static long countUsersByEmail(String email) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Long.class,
                email);
    }

    private static RegisterRequest handleRegistration(String handle) {
        return new RegisterRequest(
                IdentifierType.HANDLE,
                null,
                handle,
                null,
                "Pass1234",
                "Registered Member",
                true);
    }

    private static RegisterRequest verifiedRegistration(
            String email,
            String code) {
        return new RegisterRequest(
                IdentifierType.EMAIL,
                email,
                null,
                code,
                "Pass1234",
                null,
                true);
    }

    private static ClientInfo clientInfo() {
        return new ClientInfo("127.0.0.1", "integration-test");
    }

    private static TokenPair tokenPair(String tokenId) {
        Instant now = Instant.now();
        return new TokenPair(
                "access",
                now.plusSeconds(60),
                "refresh",
                now.plusSeconds(300),
                tokenId);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }

    static final class RegistrationEventRecorder {

        private final List<UserRegisteredEvent> events = new ArrayList<>();

        @EventListener
        public synchronized void onApplicationEvent(UserRegisteredEvent event) {
            events.add(event);
        }

        private synchronized void clear() {
            events.clear();
        }

        private synchronized List<UserRegisteredEvent> snapshot() {
            return List.copyOf(events);
        }
    }

    private static final class ForcedRegistrationRollback
            extends RuntimeException {
    }

    private static final class GatedStringRedisTemplate
            extends StringRedisTemplate {

        private final AtomicReference<ScriptGate> gate =
                new AtomicReference<>();

        private GatedStringRedisTemplate(
                LettuceConnectionFactory connectionFactory) {
            super(connectionFactory);
            afterPropertiesSet();
        }

        private ScriptGate pauseOnceAfter(String key) {
            ScriptGate next = new ScriptGate(key);
            if (!gate.compareAndSet(null, next)) {
                throw new IllegalStateException("a Redis script gate is active");
            }
            return next;
        }

        private void clearGate() {
            ScriptGate active = gate.getAndSet(null);
            if (active != null) {
                active.resume();
            }
        }

        @Override
        public <T> T execute(
                RedisScript<T> script,
                List<String> keys,
                Object... args) {
            T result = super.execute(script, keys, args);
            ScriptGate active = gate.get();
            if (active != null
                    && keys.contains(active.key)
                    && active.claim()) {
                active.reached.countDown();
                active.awaitResume();
                gate.compareAndSet(active, null);
            }
            return result;
        }
    }

    private static final class ScriptGate {

        private final String key;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        private ScriptGate(String key) {
            this.key = key;
        }

        private boolean claim() {
            return claimed.compareAndSet(false, true);
        }

        private boolean awaitReached() throws InterruptedException {
            return reached.await(5, TimeUnit.SECONDS);
        }

        private void awaitResume() {
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Redis script gate timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Redis script gate was interrupted",
                        interrupted);
            }
        }

        private void resume() {
            resume.countDown();
        }
    }
}
