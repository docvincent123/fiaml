import {ForbiddenException} from '@nestjs/common';
export const defaults:Record<string,string[]>={
 ADMIN:['dashboard','patients.read','patients.manage','rooms.manage','archive.read','cabinets.manage','tasks.read','users.manage','sessions.manage','audit.read'],
 REGISTRAR:['dashboard','patients.read','patients.manage','rooms.manage','archive.read','cabinets.manage','tasks.read'],
 DOCTOR:['dashboard','patients.read','notes.write','tasks.create','tasks.read','appointments.read'],
 NURSE:['dashboard','tasks.work','appointments.read','qr.read']
};
export type Actor={id:string;name:string;role:string;specialty:string;permissions:string[];sid:string};
export function permissions(user:any):string[]{const base=defaults[user.role]??[];return base.filter(p=>user.permissions?.[p]!==false);}
export function allow(actor:Actor,permission:string){if(!actor.permissions.includes(permission)) throw new ForbiddenException('Немає права доступу');}
