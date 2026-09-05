import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// React Testing Library only auto-cleans when test globals are enabled;
// this project imports from 'vitest' explicitly, so clean up manually.
afterEach(() => {
  cleanup();
});
