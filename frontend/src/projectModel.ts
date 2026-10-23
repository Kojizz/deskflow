export type Project = {id:string; name:string; kind:'git'|'plain'; archived:boolean};
export type ProjectInstance = {
 id:string;
 project_id:string;
 name:string;
 status:'READY'|'BUSY'|'STALE'|'ARCHIVED';
 leased:boolean;
 ref?:string;
 state_fingerprint?:string;
};
export type InstanceAction = 'fork'|'refresh'|'archive'|'delete';
export function instanceActions(instance:ProjectInstance):InstanceAction[] {
 if (instance.leased || instance.status === 'BUSY') return [];
 if (instance.status === 'READY') return ['fork','refresh','archive','delete'];
 if (instance.status === 'STALE') return ['refresh','archive','delete'];
 return ['refresh','delete'];
}
