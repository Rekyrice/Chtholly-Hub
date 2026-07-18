import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8888';
const scenario = __ENV.BENCHMARK_SCENARIO || 'all';
const variant = __ENV.BENCHMARK_VARIANT || 'default';
const seed = Number(__ENV.BENCHMARK_SEED || '20260715');
const postIds = (__ENV.BENCHMARK_POST_IDS || '920000000000000001,920000000000000002,920000000000000003,920000000000000004,920000000000000005')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
const userIds = (__ENV.BENCHMARK_USER_IDS || '910000000000000002,910000000000000003,910000000000000004,910000000000000005,910000000000000006')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
const authToken = __ENV.BENCHMARK_TOKEN || '';

export const options = {
  vus: Number(__ENV.K6_VUS || '4'),
  duration: __ENV.K6_DURATION || '60s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
  tags: {
    profile: __ENV.BENCHMARK_PROFILE || 'smoke',
    scenario,
    variant,
  },
};

function deterministicIndex(length, salt) {
  if (length === 0) {
    return 0;
  }
  const mixed = (seed + (__VU * 104729) + (__ITER * 13007) + salt) >>> 0;
  return mixed % length;
}

function headers() {
  const result = { 'Content-Type': 'application/json' };
  if (authToken) {
    result.Authorization = `Bearer ${authToken}`;
  }
  return result;
}

function readRequest() {
  const chooseDetail = deterministicIndex(10, 17) < 7;
  if (chooseDetail) {
    const postId = postIds[deterministicIndex(postIds.length, 29)];
    const response = http.get(`${baseUrl}/api/v1/posts/detail/${postId}`, {
      tags: { operation: 'post-detail' },
    });
    check(response, { 'detail status is 200': (value) => value.status === 200 });
    return;
  }

  const response = http.get(`${baseUrl}/api/v1/posts/feed?page=1&size=20`, {
    tags: { operation: 'post-feed' },
  });
  check(response, { 'feed status is 200': (value) => value.status === 200 });
}

function interactionRequest() {
  const postId = postIds[deterministicIndex(postIds.length, 43)];
  const action = deterministicIndex(2, 47) === 0 ? 'like' : 'unlike';
  const response = http.post(
    `${baseUrl}/api/v1/action/${action}`,
    JSON.stringify({ entityType: 'post', entityId: postId }),
    { headers: headers(), tags: { operation: `counter-${action}` } },
  );
  check(response, { 'interaction status is 200': (value) => value.status === 200 });
}

function relationRequest() {
  const userId = userIds[deterministicIndex(userIds.length, 59)];
  const action = deterministicIndex(2, 61) === 0 ? 'follow' : 'unfollow';
  const response = http.post(
    `${baseUrl}/api/v1/relation/${action}?toUserId=${encodeURIComponent(userId)}`,
    null,
    { headers: headers(), tags: { operation: `relation-${action}` } },
  );
  check(response, { 'relation status is 200': (value) => value.status === 200 });
}

export default function () {
  if (scenario === 'cache') {
    readRequest();
  } else if (scenario === 'counter') {
    interactionRequest();
  } else if (scenario === 'relation') {
    relationRequest();
  } else {
    const selector = deterministicIndex(100, 71);
    if (selector < 85) {
      readRequest();
    } else if (selector < 95) {
      interactionRequest();
    } else {
      relationRequest();
    }
  }
  sleep(0.05);
}
