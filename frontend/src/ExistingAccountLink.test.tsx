import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import ExistingAccountLink,{type PendingLink} from './ExistingAccountLink';

const pending:PendingLink={active:true,provider:'google',email:'existing@example.com',hasPassword:true,alreadySignedIn:false,existingProviders:[]};
const render=(value:PendingLink)=>renderToStaticMarkup(<ExistingAccountLink pending={value} onLogin={()=>{}} onCancel={()=>{}}/>);

describe('existing-account social sign-in',()=>{
 it('never asks for a DeskFlow password when continuing with Google',()=>{
  const html=render(pending);
  expect(html).toContain('Send confirmation link');
  expect(html).not.toContain('name="password"');
  expect(html).not.toContain('name="email"');
  expect(html).not.toContain('name="name"');
 });
 it('keeps password confirmation available for a GitHub collision',()=>{
  const html=render({...pending,provider:'github'});
  expect(html).toContain('name="password"');
  expect(html).toContain('Connect GitHub and sign in');
 });
 it('uses an already-connected provider for passwordless accounts',()=>{
  const html=render({...pending,provider:'github',hasPassword:false,existingProviders:['google']});
  expect(html).not.toContain('name="password"');
  expect(html).toContain('/api/auth/oauth2/start/google?confirm=true');
  expect(html).toContain('Confirm with Google');
 });
 it('does not ask a signed-in owner for their password again',()=>{
  const html=render({...pending,alreadySignedIn:true});
  expect(html).not.toContain('name="password"');
  expect(html).toContain('Connect Google and sign in');
 });
});
