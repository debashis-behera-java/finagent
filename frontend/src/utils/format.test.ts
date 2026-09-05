import { describe, expect, it } from 'vitest';
import { formatDateTime, percent, shortId, signedNumber, valueOrUnavailable } from './format';

describe('format helpers', () => {
  it('renders Unavailable for missing values, never fakes them', () => {
    expect(valueOrUnavailable(null)).toBe('Unavailable');
    expect(valueOrUnavailable(undefined)).toBe('Unavailable');
    expect(valueOrUnavailable('   ')).toBe('Unavailable');
    expect(valueOrUnavailable(0)).toBe('0');
    expect(valueOrUnavailable('AAPL')).toBe('AAPL');
  });

  it('signs changes without inventing precision', () => {
    expect(signedNumber(2.5)).toBe('+2.5');
    expect(signedNumber(-1.2)).toBe('-1.2');
    expect(signedNumber(0)).toBe('0');
    expect(signedNumber(null)).toBe('Unavailable');
    expect(signedNumber(Number.NaN)).toBe('Unavailable');
  });

  it('appends a single percent sign', () => {
    expect(percent(1.69)).toBe('1.69%');
    expect(percent('1.69%')).toBe('1.69%');
    expect(percent(null)).toBe('Unavailable');
  });

  it('formats datetimes and rejects garbage', () => {
    expect(formatDateTime(null)).toBe('Unavailable');
    expect(formatDateTime('not-a-date')).toBe('Unavailable');
    expect(formatDateTime('2026-09-04T10:00:00Z')).toContain('2026');
  });

  it('shortens long ids', () => {
    expect(shortId('f3c4d968-3356-49a8-829f-7d75f29565f2')).toContain('…');
    expect(shortId('abc')).toBe('abc');
  });
});
