const test = require('node:test');
const assert = require('node:assert/strict');
const { parseBudgetAlertData, shouldDisableBilling } = require('../lib/budgetGuard.js');

test('parseBudgetAlertData decodes base64 JSON payload', () => {
  const sample = {
    budgetDisplayName: 'hibi-monthly-infra-cap',
    costAmount: 520,
    budgetAmount: 500,
    currencyCode: 'JPY',
  };
  const encoded = Buffer.from(JSON.stringify(sample), 'utf8').toString('base64');
  const parsed = parseBudgetAlertData(encoded);
  assert.deepEqual(parsed, sample);
  assert.equal(parseBudgetAlertData('invalid-json!!'), null);
  assert.equal(parseBudgetAlertData(''), null);
});

test('shouldDisableBilling triggers only when actual cost >= budget', () => {
  assert.equal(shouldDisableBilling({ costAmount: 500, budgetAmount: 500 }), true);
  assert.equal(shouldDisableBilling({ costAmount: 650, budgetAmount: 500 }), true);
  // Forecast alert below actual budget should NOT kill billing
  assert.equal(
    shouldDisableBilling({ costAmount: 120, budgetAmount: 500, forecastThresholdExceeded: 1.0 }),
    false
  );
  assert.equal(shouldDisableBilling({ costAmount: 0, budgetAmount: 0 }), false);
  assert.equal(shouldDisableBilling(null), false);
});
