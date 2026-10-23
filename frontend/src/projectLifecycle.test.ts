import {describe, it, expect, vi, afterEach} from 'vitest';
import {instanceActions, type ProjectInstance} from './projectModel';
import {readyInstances} from './ProjectsView';

const instance:ProjectInstance = {id:'instance', project_id:'project', name:'Main', status:'READY', leased:false};
afterEach(() => vi.unstubAllGlobals());

describe('project lifecycle controls', () => {
 it('allows stale snapshots to be refreshed or removed', () => {
  expect(instanceActions({...instance, status:'STALE'})).toEqual(['refresh','archive','delete']);
 });
 it('allows archived snapshots to be restored or deleted', () => {
  expect(instanceActions({...instance, status:'ARCHIVED'})).toEqual(['refresh','delete']);
 });
 it('never offers maintenance during a lease', () => {
  expect(instanceActions({...instance, leased:true})).toEqual([]);
  expect(instanceActions({...instance, status:'STALE', leased:true})).toEqual([]);
  expect(instanceActions({...instance, status:'BUSY'})).toEqual([]);
 });
 it('does not load project paths when the feature is disabled', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({enabled:false})));
  vi.stubGlobal('fetch', fetch);
  expect(await readyInstances()).toEqual([]);
  expect(fetch).toHaveBeenCalledTimes(1);
 });
 it('only offers unleased ready instances in active projects', async () => {
  const fetch = vi.fn()
   .mockResolvedValueOnce(new Response(JSON.stringify({enabled:true})))
   .mockResolvedValueOnce(new Response(JSON.stringify([{id:'project', archived:false}, {id:'archived', archived:true}])))
   .mockResolvedValueOnce(new Response(JSON.stringify([instance, {...instance, id:'leased', leased:true}, {...instance, id:'stale', status:'STALE'}])));
  vi.stubGlobal('fetch', fetch);
  expect(await readyInstances()).toEqual([instance]);
  expect(fetch).toHaveBeenCalledTimes(3);
 });
});
