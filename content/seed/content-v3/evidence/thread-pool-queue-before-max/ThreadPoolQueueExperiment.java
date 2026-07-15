import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ThreadPoolQueueExperiment {
    private record Snapshot(
            String queue,
            int submitted,
            int poolSize,
            int largestPoolSize,
            int activeCount,
            int queued,
            int rejected) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ThreadPoolQueueExperiment <results.json>");
        }
        List<Snapshot> snapshots = List.of(
                runUnbounded(),
                runBounded());
        String json = toJson(snapshots);
        Files.writeString(Path.of(args[0]), json, StandardCharsets.UTF_8);
        System.out.print(json);
    }

    private static Snapshot runUnbounded() throws InterruptedException {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 64, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        return run(executor, "LinkedBlockingQueue(unbounded)", 40);
    }

    private static Snapshot runBounded() throws InterruptedException {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 64, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        return run(executor, "ArrayBlockingQueue(8)", 40);
    }

    private static Snapshot run(ThreadPoolExecutor executor, String queue, int submitted)
            throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        List<Runnable> tasks = new ArrayList<>();
        for (int index = 0; index < submitted; index++) {
            tasks.add(() -> {
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        int rejected = 0;
        for (Runnable task : tasks) {
            try {
                executor.execute(task);
            } catch (RuntimeException exception) {
                rejected++;
            }
        }
        Thread.sleep(200);
        Snapshot snapshot = new Snapshot(
                queue,
                submitted,
                executor.getPoolSize(),
                executor.getLargestPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                rejected);
        release.countDown();
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
        return snapshot;
    }

    private static String toJson(List<Snapshot> snapshots) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"experiment\": \"thread-pool-queue-before-max\",\n");
        json.append("  \"javaVersion\": \"").append(System.getProperty("java.version")).append("\",\n");
        json.append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n");
        json.append("  \"configuration\": {\"corePoolSize\": 4, \"maximumPoolSize\": 64, \"submitted\": 40},\n");
        json.append("  \"snapshots\": [\n");
        for (int index = 0; index < snapshots.size(); index++) {
            Snapshot value = snapshots.get(index);
            json.append("    {")
                    .append("\"queue\": \"").append(value.queue()).append("\", ")
                    .append("\"submitted\": ").append(value.submitted()).append(", ")
                    .append("\"poolSize\": ").append(value.poolSize()).append(", ")
                    .append("\"largestPoolSize\": ").append(value.largestPoolSize()).append(", ")
                    .append("\"activeCount\": ").append(value.activeCount()).append(", ")
                    .append("\"queued\": ").append(value.queued()).append(", ")
                    .append("\"rejected\": ").append(value.rejected()).append("}");
            json.append(index + 1 == snapshots.size() ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }
}
