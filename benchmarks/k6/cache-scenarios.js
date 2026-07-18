import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8888';
const scenario = __ENV.BENCHMARK_SCENARIO || 'stable-hot';
const hotPostId = __ENV.BENCHMARK_HOT_POST_ID || '920000000000000001';

if (!['stable-hot', 'expiry-spike'].includes(scenario)) {
  throw new Error(`Unsupported cache scenario: ${scenario}`);
}

export const options = {
  vus: Number(__ENV.K6_VUS || '4'),
  duration: __ENV.K6_DURATION || '30s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
  tags: {
    profile: __ENV.BENCHMARK_PROFILE || 'smoke',
    scenario,
    variant: __ENV.BENCHMARK_VARIANT || 'full',
  },
};

export default function () {
  const response = http.get(`${baseUrl}/api/v1/posts/detail/${hotPostId}`, {
    tags: { operation: scenario },
  });
  check(response, { 'detail status is 200': (value) => value.status === 200 });
  if (scenario === 'stable-hot') {
    sleep(0.05);
  }
}
