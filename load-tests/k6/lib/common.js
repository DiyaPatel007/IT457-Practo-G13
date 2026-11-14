// Shared k6 helpers. Every script loads the seed file written by scripts/seed.py:
//   k6 run -e SEED=../../scripts/.seed/seed-output.json <script>.js
import http from 'k6/http';

/** Logs a user in with Cognito USER_PASSWORD_AUTH and returns the access token (valid 60 min). */
export function cognitoLogin(seed, email) {
  const res = http.post(
    `https://cognito-idp.${seed.region}.amazonaws.com/`,
    JSON.stringify({
      AuthFlow: 'USER_PASSWORD_AUTH',
      ClientId: seed.clientId,
      AuthParameters: { USERNAME: email, PASSWORD: seed.password },
    }),
    {
      headers: {
        'Content-Type': 'application/x-amz-json-1.1',
        'X-Amz-Target': 'AWSCognitoIdentityProviderService.InitiateAuth',
      },
      tags: { name: 'cognito-login' },
    },
  );
  if (res.status !== 200) {
    throw new Error(`Cognito login failed for ${email}: ${res.status} ${res.body}`);
  }
  return res.json('AuthenticationResult.AccessToken');
}

/** Logs in every seeded patient ONCE (in setup), never per request: Cognito has rate limits. */
export function patientTokens(seed, count) {
  const n = Math.min(count || seed.patients.length, seed.patients.length);
  const tokens = [];
  for (let i = 0; i < n; i++) {
    tokens.push(cognitoLogin(seed, seed.patients[i].email));
  }
  return tokens;
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export function jsonHeaders(token, name) {
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return { headers, tags: { name } };
}

/** Future slots only: past slots cannot be booked. */
export function futureSlots(doctor) {
  const now = Date.now();
  return doctor.slots.filter((s) => Date.parse(s) > now + 60 * 1000);
}

export function baseUrl(seed) {
  return (__ENV.BASE_URL || seed.apiUrl).replace(/\/$/, '');
}
