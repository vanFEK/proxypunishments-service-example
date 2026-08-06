// k6 load profile for proxypunishments-service.
//
// The mix mirrors what a live network actually does: almost every request is a login check, a
// handful are moderator actions. Testing the write path in isolation would measure something no
// server ever experiences.
//
//   k6 run -e TOKEN=dev-local-token -e HOST=http://localhost:8080 docs/load/punishments.js

import http from 'k6/http';
import { check, group } from 'k6';
import { Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const HOST = __ENV.HOST || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || 'dev-local-token';

const headers = { 'X-Service-Token': TOKEN, 'Content-Type': 'application/json' };

const lookupLatency = new Trend('active_lookup_ms', true);

export const options = {
    scenarios: {
        logins: {
            executor: 'ramping-arrival-rate',
            startRate: 50,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 400,
            stages: [
                { target: 200, duration: '30s' },
                { target: 800, duration: '2m' },
                { target: 800, duration: '2m' },
                { target: 0, duration: '30s' },
            ],
            exec: 'activeLookup',
        },
        moderation: {
            executor: 'constant-arrival-rate',
            rate: 5,
            timeUnit: '1s',
            duration: '5m',
            preAllocatedVUs: 10,
            exec: 'issueAndLift',
        },
    },
    thresholds: {
        'active_lookup_ms': ['p(99)<50'],
        'http_req_failed': ['rate<0.001'],
    },
};

const POOL_SIZE = 10000;
const pool = new Array(POOL_SIZE).fill(null).map(() => uuidv4());

export function activeLookup() {
    const uuid = pool[Math.floor(Math.random() * POOL_SIZE)];
    const response = http.get(`${HOST}/api/v1/punishments/${uuid}/active`, { headers });

    lookupLatency.add(response.timings.duration);

    check(response, { 'lookup answered': (r) => r.status === 200 || r.status === 404 });
}

export function issueAndLift() {
    const uuid = uuidv4();

    group('issue', () => {
        const body = JSON.stringify({
            playerUuid: uuid,
            kind: 'BAN',
            issuedByUuid: '00000000-0000-0000-0000-000000000000',
            issuedByName: 'LoadTest',
            reason: 'load test',
            durationSeconds: 3600,
        });

        const response = http.post(`${HOST}/api/v1/punishments`, body, { headers });

        check(response, { 'issued': (r) => r.status === 201 });
    });

    group('lift', () => {
        const body = JSON.stringify({
            liftedByUuid: '00000000-0000-0000-0000-000000000000',
            liftedByName: 'LoadTest',
            reason: 'load test',
        });

        const response = http.del(`${HOST}/api/v1/punishments/${uuid}?kind=BAN`, body, { headers });

        check(response, { 'lifted': (r) => r.status === 204 });
    });
}
