import {describe,it,expect} from 'vitest';
import {templates,validateGraph,node,outputHandles} from './model';
describe('workflow graph validation',()=>{
 it('accepts every shipped template',()=>{for(const t of templates)expect(validateGraph(t.definition)).toBeNull();});
 it('rejects disconnected steps',()=>expect(validateGraph({nodes:[node('a','manual','Start',0,0),node('b','transform','Map',100,0)],edges:[]})).toContain('Connect every step'));
 it('rejects cycles',()=>expect(validateGraph({nodes:[node('a','manual','Start',0,0),node('b','transform','Map',100,0)],edges:[{id:'1',source:'a',target:'b'},{id:'2',source:'b',target:'a'}]})).toContain('cycle'));
 it('rejects multiple triggers',()=>expect(validateGraph({nodes:[node('a','manual','Start',0,0),node('b','schedule','Timer',100,0)],edges:[]})).toContain('exactly one'));
 it('rejects removed switch paths',()=>expect(validateGraph({nodes:[node('a','manual','Start',0,0),node('b','switch','Route',1,0),node('c','merge','Join',2,0)],edges:[{id:'1',source:'a',target:'b'},{id:'2',source:'b',target:'c',sourceHandle:'deleted'}]})).toContain('output path'));
 it('exposes approval and recovery handles',()=>expect(outputHandles(node('a','approval','Review',0,0,{title:'Review',onError:'route'}).data)).toEqual(['true','false','error']));
 it('rejects malformed imports before rendering',()=>expect(validateGraph({nodes:[{id:'bad',data:{kind:'unsupported'}}],edges:[]} as any)).toContain('unsupported'));
});
