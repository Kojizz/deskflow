import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import AccountRecovery,{recoveryMode,type RecoveryMode} from './AccountRecovery';
const render=(mode:RecoveryMode,token='')=>renderToStaticMarkup(<AccountRecovery mode={mode} token={token} onBack={()=>{}} onLogin={()=>{}} onReset={()=>{}}/>);
describe('account recovery',()=>{
 it('requests only an email for forgot password',()=>{
  const html=render('forgot');
  expect(html).toContain('name="email"');expect(html).toContain('Send reset link');expect(html).not.toContain('type="password"');
 });
 it('offers matching new-password fields for a complete reset link',()=>{
  const html=render('reset','a'.repeat(43));
  expect(html).toContain('name="password"');expect(html).toContain('name="confirmPassword"');expect(html).toContain('autoComplete="new-password"');
 });
 it('does not let an incomplete email link submit',()=>{
  const html=render('reset');expect(html).toContain('This link is invalid or expired.');expect(html).not.toContain('Save new password');
 });
 it('email confirmation does not request a password',()=>{
  const html=render('confirm-email','b'.repeat(43));expect(html).toContain('Confirm and sign in');expect(html).not.toContain('type="password"');
 });
 it('recognizes recovery routes without treating ordinary sign-in as recovery',()=>{
  expect(recoveryMode('?auth=reset')).toBe('reset');expect(recoveryMode('?auth=forgot')).toBe('forgot');expect(recoveryMode('?auth=confirm-email')).toBe('confirm-email');expect(recoveryMode('?auth=signin')).toBeNull();
 });
});
