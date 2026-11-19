import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  add,
  allocate,
  cashRound,
  format,
  money,
  multiply,
  parse,
  percentage,
  subtract,
  toDecimal,
} from "../src/index.ts";

const etb = (minor: number) => money(minor, "ETB");

describe("parsing and printing", () => {
  test("decimal strings are exact", () => {
    assert.deepEqual(parse("1500", "ETB"), etb(150_000));
    assert.deepEqual(parse("1,500.5", "ETB"), etb(150_050));
    assert.deepEqual(parse("-0.07", "ETB"), etb(-7));
    assert.deepEqual(parse("2500", "UGX"), money(2500, "UGX"));
    assert.throws(() => parse("1.234", "ETB"));
    assert.throws(() => parse("10.5", "UGX"));
    assert.throws(() => parse("1,50", "ETB"));
    assert.equal(toDecimal(etb(150_050)), "1500.50");
    assert.equal(toDecimal(etb(-7)), "-0.07");
    assert.equal(toDecimal(money(2500, "UGX")), "2500");
  });
  test("round trip for many amounts", () => {
    for (let minor = -100_000; minor <= 100_000; minor += 37) {
      assert.deepEqual(parse(toDecimal(etb(minor)), "ETB"), etb(minor));
    }
  });
  test("localized display", () => {
    assert.match(format(etb(150_000), "en-ET"), /1,500\.00/);
    assert.match(format(etb(150_000), "am-ET"), /1,500\.00/);
    assert.match(format(money(123_456, "KES"), "en-KE", { cash: true }), /1,235\.00/);
    // Large amounts are not routed through floating point.
    assert.match(format(etb(9_007_199_254_740_991), "en"), /90,071,992,547,409\.91/);
  });
});

describe("arithmetic", () => {
  test("add, subtract, multiply keep the currency", () => {
    assert.deepEqual(add(etb(100), etb(250), etb(-50)), etb(300));
    assert.deepEqual(subtract(etb(100), etb(250)), etb(-150));
    assert.deepEqual(multiply(etb(17_250), 3), etb(51_750));
    assert.throws(() => add(etb(1), money(1, "KES")));
    assert.throws(() => multiply(etb(1), 1.5));
    assert.throws(() => etb(Number.MAX_SAFE_INTEGER + 1));
  });
  test("percentages round half up per line", () => {
    assert.deepEqual(percentage(etb(150_000), 300), etb(4_500));
    assert.deepEqual(percentage(etb(1_050), 50), etb(5)); // 5.25 → 5
    assert.deepEqual(percentage(etb(1_100), 50), etb(6)); // 5.5 → 6
    assert.deepEqual(percentage(etb(-1_100), 50), etb(-6));
  });
  test("allocation never loses or invents a minor unit", () => {
    assert.deepEqual(allocate(etb(10_000), [1, 1, 1]), [etb(3_334), etb(3_333), etb(3_333)]);
    assert.deepEqual(allocate(etb(-10_000), [1, 1, 1]), [etb(-3_334), etb(-3_333), etb(-3_333)]);
    for (let amount = 0; amount < 2_000; amount += 7) {
      for (const weights of [[1], [1, 2], [3, 3, 4], [0, 5, 1], [7, 11, 13, 17]]) {
        const parts = allocate(etb(amount), weights);
        assert.equal(
          parts.reduce((s, p) => s + p.amountMinor, 0),
          amount,
        );
        weights.forEach((w, i) => {
          const exact = (amount * w) / weights.reduce((s, x) => s + x, 0);
          assert.ok(Math.abs(parts[i]!.amountMinor - exact) < 1, `${amount} ${weights}`);
        });
      }
    }
  });
  test("cash rounding uses the smallest coin", () => {
    assert.deepEqual(cashRound(money(123_449, "KES")), money(123_400, "KES"));
    assert.deepEqual(cashRound(money(123_450, "KES")), money(123_500, "KES"));
    assert.deepEqual(cashRound(money(1_224, "UGX")), money(1_200, "UGX"));
    assert.deepEqual(cashRound(etb(150_037)), etb(150_037));
  });
});
