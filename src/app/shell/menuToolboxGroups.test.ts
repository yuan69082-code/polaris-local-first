import { describe, expect, it } from 'vitest';
import type { PolarisToolPromptGroup } from '../../engines/tool-protocol/assistantToolProtocolTypes';
import {
  countEnabledVisibleToolboxGroups,
  getVisibleToolboxPromptGroups
} from './menuToolboxGroups';

const allEnabled = {
  environment: true,
  task: true,
  room: true,
  project: false,
  desktop: true,
  device: true,
  theme: true,
  attachment: true,
  generation: true,
  archive: true,
  web: true,
  personalData: true,
  mcp: true,
  knowledge: true,
  memory: true,
  memoryRecall: true,
  memoryWrite: true,
  proactive: true
} satisfies Record<PolarisToolPromptGroup, boolean>;

describe('menuToolboxGroups', () => {
  it('hides desktop local tools when the Mac host bridge is unavailable', () => {
    expect(getVisibleToolboxPromptGroups({ desktopLocalAvailable: false })).not.toContain('desktop');
  });

  it('shows desktop local tools only for the Mac host bridge', () => {
    expect(getVisibleToolboxPromptGroups({ desktopLocalAvailable: true })).toContain('desktop');
  });

  it('counts only visible enabled groups', () => {
    expect(countEnabledVisibleToolboxGroups(allEnabled, { desktopLocalAvailable: false }))
      .toBe(countEnabledVisibleToolboxGroups(allEnabled, { desktopLocalAvailable: true }) - 1);
  });
});
