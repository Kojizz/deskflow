export class ApiError extends Error { constructor(message:string,public status:number){super(message);this.name='ApiError';} }
export async function api<T=any>(path:string,options:RequestInit={}):Promise<T> {
 const response=await fetch(`/api${path}`,{...options,credentials:'same-origin',headers:{'Content-Type':'application/json','X-Deskflow-Request':'1',...options.headers}});
 const body=await response.json().catch(()=>({error:'The server returned an unexpected response.'}));
 if(!response.ok)throw new ApiError(body.error??`Request failed (${response.status})`,response.status);
 return body;
}
export const post=<T=any>(path:string,body:unknown={})=>api<T>(path,{method:'POST',body:JSON.stringify(body)});
