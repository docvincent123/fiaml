import {ForbiddenException} from '@nestjs/common';
export const defaults:Record<string,string[]>={
 ADMIN:['dashboard','patients.read','patients.manage','rooms.manage','archive.read','cabinets.manage','tasks.read','users.manage','sessions.manage','audit.read'],
 REGISTRAR:['dashboard','patients.read','patients.manage','rooms.read','appointments.read','appointments.manage'],
 DOCTOR:['dashboard','patients.read','notes.write','tasks.create','tasks.read','appointments.read'],
 NURSE:['dashboard','tasks.work','appointments.read','qr.read','patients.read','observations.write','messages.use','shift.handover'],
 THERAPIST:['dashboard','patients.read','tasks.work','appointments.read','qr.read','rehab.write','messages.use','shift.handover']
};
defaults.ADMIN.push('documents.manage','messages.use','messages.send','rooms.read','appointments.read','appointments.manage');
defaults.REGISTRAR.push('documents.manage','messages.use');
for(const role of ['DOCTOR','NURSE','THERAPIST'])defaults[role].push('rooms.read');
defaults.DOCTOR.push('appointments.manage','handover.manage','observations.write','clinical.write','rehab.write','documents.manage','messages.use');
export type Actor={id:string;name:string;role:string;specialty:string;role_label?:string;permissions:string[];sid:string};
export function permissions(user:any):string[]{const base=defaults[user.role]??[];return base.filter(p=>user.permissions?.[p]!==false);}
export function allow(actor:Actor,permission:string){if((permission==='archive.read'&&actor.role!=='ADMIN')||!actor.permissions.includes(permission)) throw new ForbiddenException('Немає права доступу');}
