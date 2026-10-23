import {describe, it, expect} from 'vitest';
import {catalog, node, validateGraph} from './model';

describe('project step models', () => {
 for (const kind of ['project.command','project.read','project.git'] as const) {
  it(`provides an independent ${kind} config`, () => {
   const step = node('project_step',kind,kind,0,0);
   expect(step.data.config).toEqual(catalog[kind].config);
   expect(step.data.config).not.toBe(catalog[kind].config);
   expect(validateGraph({nodes:[node('start','manual','Start',0,0),step],edges:[{id:'edge',source:'start',target:'project_step'}]})).toBeNull();
  });
 }
 it('keeps argv as separate template entries', () => {
  expect(Array.isArray(catalog['project.command'].config.argv)).toBe(true);
  expect(catalog['project.command'].config.timeoutSeconds).toBeLessThanOrEqual(60);
 });
});
