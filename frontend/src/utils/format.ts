/** Display helpers. Missing backend values render as "Unavailable" — never faked. */

export const UNAVAILABLE = 'Unavailable';

export function valueOrUnavailable(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return UNAVAILABLE;
  if (typeof value === 'string' && value.trim() === '') return UNAVAILABLE;
  return String(value);
}

export function signedNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return UNAVAILABLE;
  return value > 0 ? `+${value}` : String(value);
}

export function percent(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return UNAVAILABLE;
  const text = String(value).trim();
  if (text === '') return UNAVAILABLE;
  return text.endsWith('%') ? text : `${text}%`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return UNAVAILABLE;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return UNAVAILABLE;
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function shortId(id: string): string {
  return id.length > 13 ? `${id.slice(0, 8)}…` : id;
}
