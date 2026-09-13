import {BadRequestException,ConflictException,ForbiddenException,NotFoundException,UnauthorizedException} from '@nestjs/common';
import {SignJWT,jwtVerify} from 'jose';
import {z} from 'zod';
import {Database,type Queryable} from './db';
import {allow,permissions,defaults,type Actor} from './access';
import {hashPassword,verifyPassword} from './security';
const uuid=z.string().uuid(), text=z.string().trim().min(1).max(200), date=z.string().datetime({offset:true});
const optionalId=uuid.nullable().optional();
const role=z.enum(['ADMIN','REGISTRAR','DOCTOR','NURSE','THERAPIST']);
export class Clinic {
 key:Uint8Array;
 constructor(public db:Database, secret=process.env.JWT_SECRET){if(!secret||secret.length<32) throw new Error('JWT_SECRET must contain at least 32 characters');this.key=new TextEncoder().encode(secret);}
 async bootstrap(){
  const pwd=process.env.ADMIN_PASSWORD;
  await this.db.tx(async c=>{await c.query('SELECT pg_advisory_xact_lock(780125)');
   if(!(await c.query('SELECT 1 FROM users LIMIT 1')).rows.length){if(!pwd||pwd.length<12) throw new Error('First start requires ADMIN_PASSWORD (12+ characters)');await c.query("INSERT INTO users(name,login,password_hash,role) VALUES('Адміністратор',$1,$2,'ADMIN')",[process.env.ADMIN_LOGIN||'admin',await hashPassword(pwd)]);}
  });
 }
 async audit(c:Queryable,a:Actor|null,action:string,id?:string){await c.query('INSERT INTO audit_events(actor_id,action,entity_id) VALUES($1,$2,$3)',[a?.id??null,action,id??null]);}
 async changed(c:Queryable){await c.query('INSERT INTO outbox DEFAULT VALUES');}
 async login(input:any,ip:string){
  const b=z.object({login:text,password:z.string().min(1).max(256),device:text.default('Пристрій')}).parse(input);
  // Serialize attempts per account, persists across server restart; no password/token in logs.
  const result=await this.db.tx(async c=>{
   await c.query('INSERT INTO login_attempts(key) VALUES($1) ON CONFLICT DO NOTHING',[b.login.toLowerCase()]);
   const attempt=(await c.query('SELECT * FROM login_attempts WHERE key=$1 FOR UPDATE',[b.login.toLowerCase()])).rows[0];
   if(attempt.blocked_until && new Date(attempt.blocked_until)>new Date()) return null;
   const u=(await c.query('SELECT * FROM users WHERE login=$1 AND active FOR UPDATE',[b.login.toLowerCase()])).rows[0];
   const valid=await verifyPassword(b.password,u?.password_hash??'00000000000000000000000000000000:'+ '00'.repeat(64));
   if(!u||!valid){await c.query("UPDATE login_attempts SET failures=CASE WHEN window_at<now()-interval '15 minutes' THEN 1 ELSE failures+1 END, blocked_until=CASE WHEN failures>=4 AND window_at>=now()-interval '15 minutes' THEN now()+interval '15 minutes' ELSE NULL END, window_at=CASE WHEN window_at<now()-interval '15 minutes' THEN now() ELSE window_at END WHERE key=$1",[b.login.toLowerCase()]);return null;}
   await c.query('DELETE FROM login_attempts WHERE key=$1',[b.login.toLowerCase()]);
   const s=(await c.query("INSERT INTO sessions(user_id,device,ip,expires_at) VALUES($1,$2,$3,now()+interval '14 hours') RETURNING id",[u.id,b.device,ip])).rows[0];
   if(u.role!=='ADMIN'&&!(await c.query('SELECT id FROM shifts WHERE user_id=$1 AND starts_at<=now() AND ends_at>now()',[u.id])).rows.length){await c.query("INSERT INTO shift_requests(user_id) VALUES($1) ON CONFLICT(user_id) WHERE status='PENDING' DO NOTHING",[u.id]);}
   await this.audit(c,null,'session.login',s.id);
   return {u,s};
  });
  if(!result) throw new UnauthorizedException('Неправильний логін/пароль або вхід тимчасово заблоковано');
  const {u,s}=result;
  const token=await new SignJWT({sid:s.id,role:u.role,permissions:permissions(u)}).setProtectedHeader({alg:'HS256'}).setSubject(u.id).setIssuer('quremed-local').setAudience('rehaflow').setIssuedAt().setExpirationTime('14h').sign(this.key);
  return {token,user:{id:u.id,name:u.name,role:u.role,specialty:u.specialty,role_label:u.role_label,permissions:permissions(u),sid:s.id}};
 }
 async authenticate(token:string):Promise<Actor>{
  try{const {payload}=await jwtVerify(token,this.key,{issuer:'quremed-local',audience:'rehaflow',algorithms:['HS256']});
   const u=(await this.db.query('SELECT u.*,s.id AS sid FROM users u JOIN sessions s ON s.user_id=u.id WHERE u.id=$1 AND s.id=$2 AND u.active AND s.revoked_at IS NULL AND s.expires_at>now()',[payload.sub,payload.sid])).rows[0];
   if(!u) throw new Error(); return {id:u.id,name:u.name,role:u.role,specialty:u.specialty,role_label:u.role_label,permissions:permissions(u),sid:u.sid};
  }catch{throw new UnauthorizedException('Сесію завершено. Увійдіть знову');}
 }
 async onShift(a:Actor,c:Queryable=this.db){return !!(await c.query('SELECT 1 FROM shifts WHERE user_id=$1 AND starts_at<=now() AND ends_at>now()',[a.id])).rows.length;}
 async me(a:Actor){await this.db.query('UPDATE sessions SET last_seen_at=now() WHERE id=$1',[a.sid]);return {...a,onShift:await this.onShift(a)};}
 async logout(a:Actor){await this.db.tx(async c=>{await c.query('UPDATE sessions SET revoked_at=now() WHERE id=$1',[a.sid]);await c.query("UPDATE shift_requests SET status='REJECTED',decided_at=now(),decided_by=$1 WHERE user_id=$1 AND status='PENDING'",[a.id]);});return {ok:true};}
 async sessions(a:Actor){allow(a,'sessions.manage');return (await this.db.query("SELECT s.id,s.device,s.ip,s.created_at,s.last_seen_at,s.expires_at,s.revoked_at,u.name,u.role,(s.revoked_at IS NULL AND s.expires_at>now() AND s.last_seen_at>now()-interval '90 seconds') AS online FROM sessions s JOIN users u ON u.id=s.user_id ORDER BY s.created_at DESC LIMIT 300")).rows;}
 async revoke(a:Actor,id:string){allow(a,'sessions.manage');uuid.parse(id);await this.db.tx(async c=>{if(!(await c.query('UPDATE sessions SET revoked_at=now() WHERE id=$1 RETURNING id',[id])).rows.length) throw new NotFoundException();await this.audit(c,a,'session.revoked',id);});return {ok:true};}
 async changePassword(a:Actor,input:any){const b=z.object({currentPassword:z.string().max(256),newPassword:z.string().min(1).max(256)}).parse(input);
  if(a.role==='ADMIN'&&b.newPassword.length<12)throw new BadRequestException('Пароль адміністратора: мінімум 12 символів');
  const u=(await this.db.query('SELECT password_hash FROM users WHERE id=$1',[a.id])).rows[0];if(!await verifyPassword(b.currentPassword,u.password_hash)) throw new ForbiddenException('Поточний пароль неправильний');
  await this.db.tx(async c=>{await c.query('UPDATE users SET password_hash=$1 WHERE id=$2',[await hashPassword(b.newPassword),a.id]);await c.query('UPDATE sessions SET revoked_at=now() WHERE user_id=$1',[a.id]);await this.audit(c,a,'password.changed',a.id);});return {ok:true};
 }
 async users(a:Actor){allow(a,'users.manage');return (await this.db.query('SELECT id,name,login,role,role_label,specialty,permissions,active FROM users ORDER BY name')).rows;}
 async staff(a:Actor){if(!a.permissions.some(p=>['patients.read','patients.manage','users.manage'].includes(p))) throw new ForbiddenException();return (await this.db.query("SELECT id,name,role,specialty FROM users WHERE active AND role IN ('DOCTOR','NURSE') ORDER BY name")).rows;}
 async saveUser(a:Actor,input:any,id?:string){allow(a,'users.manage');if(id) uuid.parse(id);
  const b=z.object({name:text,login:z.string().trim().toLowerCase().regex(/^[a-z0-9._-]{3,64}$/),role,specialty:z.string().max(200).default(''),role_label:z.string().trim().max(100).default(''),password:z.string().min(1).max(256).optional(),active:z.boolean().default(true),permissions:z.record(z.string(),z.boolean()).default({})}).parse(input);
  if(b.role==='ADMIN'&&b.password&&b.password.length<12)throw new BadRequestException('Пароль адміністратора: мінімум 12 символів');
  if(b.role==='DOCTOR'&&!b.specialty.trim()) throw new BadRequestException('Вкажіть спеціальність');
  if(Object.keys(b.permissions).some(p=>!defaults[b.role].includes(p))) throw new BadRequestException('Право не належить цій ролі');
  if(!id&&!b.password) throw new BadRequestException('Вкажіть пароль');
  return this.db.tx(async c=>{await c.query('SELECT pg_advisory_xact_lock(780126)');
   if(id){const old=(await c.query('SELECT * FROM users WHERE id=$1 FOR UPDATE',[id])).rows[0];if(!old) throw new NotFoundException();
    if(old.role==='ADMIN'&&(!b.active||b.role!=='ADMIN'||b.permissions['users.manage']===false)){
     const others=(await c.query("SELECT id FROM users WHERE role='ADMIN' AND active AND id<>$1 AND COALESCE((permissions->>'users.manage')::boolean,true)",[id])).rows;
     if(!others.length) throw new ConflictException('Потрібен хоча б один активний адміністратор');
    }
    await c.query('UPDATE users SET name=$1,login=$2,role=$3,specialty=$4,active=$5,permissions=$6,password_hash=COALESCE($7,password_hash) WHERE id=$8',[b.name,b.login,b.role,b.specialty,b.active,b.permissions,b.password?await hashPassword(b.password):null,id]);
    await c.query('UPDATE sessions SET revoked_at=now() WHERE user_id=$1',[id]);
   }else{id=(await c.query('INSERT INTO users(name,login,role,specialty,active,permissions,password_hash) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id',[b.name,b.login,b.role,b.specialty,b.active,b.permissions,await hashPassword(b.password!)])).rows[0].id;}
   await c.query('UPDATE users SET role_label=$2 WHERE id=$1',[id,b.role_label]);await this.audit(c,a,'user.saved',id);return {id};
  });
 }
 async shiftRequests(a:Actor){if(a.role!=='ADMIN')throw new ForbiddenException();return (await this.db.query("SELECT r.*,u.name,u.role,u.role_label FROM shift_requests r JOIN users u ON u.id=r.user_id WHERE r.status='PENDING' ORDER BY r.requested_at")).rows;}
 async approveShift(a:Actor,requestId:string,approve:boolean){if(a.role!=='ADMIN')throw new ForbiddenException();uuid.parse(requestId);return this.db.tx(async c=>{const ref=(await c.query('SELECT user_id FROM shift_requests WHERE id=$1',[requestId])).rows[0];if(!ref)throw new NotFoundException();const user=(await c.query('SELECT active,role FROM users WHERE id=$1 FOR UPDATE',[ref.user_id])).rows[0];if(!user?.active||user.role==='ADMIN')throw new ConflictException('Працівник недоступний');const r=(await c.query("SELECT * FROM shift_requests WHERE id=$1 AND status='PENDING' FOR UPDATE",[requestId])).rows[0];if(!r)throw new ConflictException('Запит уже опрацьовано');if(approve&&!(await c.query('SELECT 1 FROM shifts WHERE user_id=$1 AND ends_at>now() AND starts_at<=now()',[r.user_id])).rows.length)await c.query("INSERT INTO shifts(user_id,ends_at) VALUES($1,now()+interval '12 hours')",[r.user_id]);await c.query('UPDATE shift_requests SET status=$2,decided_by=$3,decided_at=now() WHERE id=$1',[requestId,approve?'APPROVED':'REJECTED',a.id]);await this.audit(c,a,approve?'shift.approved':'shift.rejected',requestId);await this.changed(c);return {ok:true};});}
 async shift(a:Actor,input:any){if(a.role==='ADMIN')throw new ForbiddenException('Адміністратору не потрібна робоча зміна');const b=z.object({start:z.boolean()}).parse(input);
  await this.db.tx(async c=>{await c.query('SELECT id FROM users WHERE id=$1 FOR UPDATE',[a.id]);if(b.start){if(!await this.onShift(a,c)) await c.query("INSERT INTO shift_requests(user_id) VALUES($1) ON CONFLICT(user_id) WHERE status='PENDING' DO NOTHING",[a.id]);}else{
   if((await c.query("SELECT 1 FROM tasks WHERE taken_by=$1 AND status='IN_PROGRESS'",[a.id])).rows.length) throw new ConflictException('Спочатку завершіть або поверніть свої завдання');
   await c.query('UPDATE shifts SET ends_at=now() WHERE user_id=$1 AND ends_at>now()',[a.id]);
  }await this.audit(c,a,b.start?'shift.requested':'shift.ended',a.id);});return {onShift:await this.onShift(a)};
 }
 async dashboard(a:Actor){allow(a,'dashboard');
  if(a.role==='NURSE') return {active:0,beds:0,open:await this.onShift(a)?Number((await this.db.query("SELECT count(*) FROM tasks WHERE status='OPEN'")).rows[0].count):0,mine:Number((await this.db.query("SELECT count(*) FROM tasks WHERE taken_by=$1 AND status='IN_PROGRESS'",[a.id])).rows[0].count)};
  return (await this.db.query("SELECT (SELECT count(*)::int FROM patients WHERE status='ACTIVE') AS active,(SELECT count(*)::int FROM beds WHERE deleted_at IS NULL) AS beds,(SELECT count(*)::int FROM admissions WHERE discharged_at IS NULL AND bed_id IS NOT NULL) AS occupied,(SELECT count(*)::int FROM tasks WHERE status='OPEN') AS open,(SELECT count(*)::int FROM tasks WHERE status='COMPLETED' AND completed_at>=current_date) AS completed,(SELECT count(*)::int FROM patients WHERE status='ARCHIVED') AS archived")).rows[0];
 }
 async patients(a:Actor,query:any){const archived=query.status==='ARCHIVED';allow(a,archived?'archive.read':'patients.read');const search=z.string().max(200).parse(query.q??'');
  return (await this.db.query("SELECT p.*,a.id AS admission_id,a.doctor_id,u.name AS doctor_name,b.id AS bed_id,b.bed_number,r.room_number FROM patients p LEFT JOIN admissions a ON a.patient_id=p.id AND a.discharged_at IS NULL LEFT JOIN users u ON u.id=a.doctor_id LEFT JOIN beds b ON b.id=a.bed_id LEFT JOIN rooms r ON r.id=b.room_id WHERE p.status=$1 AND p.name ILIKE $2 AND ($3::uuid IS NULL OR a.doctor_id=$3) ORDER BY p.name LIMIT 500",[archived?'ARCHIVED':'ACTIVE','%'+search+'%',query.mine==='true'&&a.role==='DOCTOR'?a.id:null])).rows;
 }
 async patient(a:Actor,id:string,qr=false){uuid.parse(id);const p=(await this.db.query('SELECT * FROM patients WHERE id=$1',[id])).rows[0];if(!p) throw new NotFoundException();
  if(qr&&a.role==='NURSE'){allow(a,'qr.read');if(!await this.onShift(a)||p.status!=='ACTIVE') throw new ForbiddenException();return {id:p.id,name:p.name,status:p.status};}
  allow(a,p.status==='ARCHIVED'?'archive.read':'patients.read');await this.audit(this.db,a,'patient.read',id);
  return {...p,admissions:(await this.db.query('SELECT a.*,b.bed_number,r.room_number,u.name AS doctor_name FROM admissions a LEFT JOIN beds b ON b.id=a.bed_id LEFT JOIN rooms r ON r.id=b.room_id LEFT JOIN users u ON u.id=a.doctor_id WHERE patient_id=$1 ORDER BY admitted_at DESC',[id])).rows,notes:(await this.db.query('SELECT n.*,u.name AS author FROM medical_notes n JOIN users u ON u.id=n.author_id WHERE patient_id=$1 ORDER BY created_at DESC',[id])).rows};
 }
 async validateBed(c:Queryable,id?:string|null){if(id){const b=(await c.query('SELECT id FROM beds WHERE id=$1 AND deleted_at IS NULL FOR UPDATE',[id])).rows[0];if(!b)throw new ConflictException('Ліжко видалено або не існує. Оновіть список.');}}
 async removeBed(a:Actor,id:string){allow(a,'rooms.manage');uuid.parse(id);return this.db.tx(async c=>{await this.validateBed(c,id);if((await c.query('SELECT 1 FROM admissions WHERE bed_id=$1 AND discharged_at IS NULL',[id])).rows.length)throw new ConflictException('Ліжко зайняте. Спочатку переведіть або випишіть пацієнта.');await c.query('UPDATE beds SET deleted_at=now() WHERE id=$1',[id]);await this.audit(c,a,'bed.deleted',id);await this.changed(c);return {ok:true};});}
 async removeRoom(a:Actor,id:string){allow(a,'rooms.manage');uuid.parse(id);return this.db.tx(async c=>{if(!(await c.query('SELECT id FROM rooms WHERE id=$1 AND deleted_at IS NULL FOR UPDATE',[id])).rows.length)throw new NotFoundException('Палату вже видалено');if((await c.query('SELECT 1 FROM beds WHERE room_id=$1 AND deleted_at IS NULL',[id])).rows.length)throw new ConflictException('Спочатку видаліть усі ліжка в палаті.');await c.query('UPDATE rooms SET deleted_at=now() WHERE id=$1',[id]);await this.audit(c,a,'room.deleted',id);await this.changed(c);return {ok:true};});}
 async validateDoctor(c:Queryable,id?:string|null){if(id&&!(await c.query("SELECT 1 FROM users WHERE id=$1 AND role='DOCTOR' AND active",[id])).rows.length) throw new BadRequestException('Лікаря не знайдено');}
 async register(a:Actor,input:any){allow(a,'patients.manage');const b=z.object({name:text,birth_date:z.string().date().nullable().optional(),phone:z.string().max(50).default(''),bed_id:optionalId,doctor_id:optionalId}).parse(input);
  return this.db.tx(async c=>{await this.validateDoctor(c,b.doctor_id);await this.validateBed(c,b.bed_id);const p=(await c.query('INSERT INTO patients(name,birth_date,phone) VALUES($1,$2,$3) RETURNING *',[b.name,b.birth_date??null,b.phone])).rows[0];
   await c.query('INSERT INTO admissions(patient_id,bed_id,doctor_id) VALUES($1,$2,$3)',[p.id,b.bed_id??null,b.doctor_id??null]);await this.audit(c,a,'patient.registered',p.id);await this.changed(c);return p;});
 }
 async editPatient(a:Actor,id:string,input:any){allow(a,'patients.manage');uuid.parse(id);const b=z.object({name:text,birth_date:z.string().date().nullable().optional(),phone:z.string().max(50).default(''),bed_id:optionalId,doctor_id:optionalId}).parse(input);
  return this.db.tx(async c=>{await this.activePatient(c,id);await this.validateDoctor(c,b.doctor_id);await this.validateBed(c,b.bed_id);await c.query('UPDATE patients SET name=$1,birth_date=$2,phone=$3 WHERE id=$4',[b.name,b.birth_date??null,b.phone,id]);await c.query('UPDATE admissions SET bed_id=$1,doctor_id=$2 WHERE patient_id=$3 AND discharged_at IS NULL',[b.bed_id??null,b.doctor_id??null,id]);await this.audit(c,a,'patient.updated',id);await this.changed(c);return {id};});
 }
 async activePatient(c:Queryable,id:string){const p=(await c.query('SELECT status FROM patients WHERE id=$1 FOR UPDATE',[id])).rows[0];if(!p) throw new NotFoundException();if(p.status!=='ACTIVE') throw new ConflictException('Пацієнта виписано');}
 async discharge(a:Actor,id:string){allow(a,'patients.manage');uuid.parse(id);return this.db.tx(async c=>{await this.activePatient(c,id);await c.query("UPDATE tasks SET status='CANCELLED' WHERE patient_id=$1 AND status IN ('OPEN','IN_PROGRESS')",[id]);await c.query("UPDATE appointments SET status='CANCELLED' WHERE patient_id=$1 AND status='BOOKED' AND ends_at>now()",[id]);await c.query('UPDATE admissions SET discharged_at=now() WHERE patient_id=$1 AND discharged_at IS NULL',[id]);await c.query("UPDATE patients SET status='ARCHIVED' WHERE id=$1",[id]);await this.audit(c,a,'patient.discharged',id);await this.changed(c);return {ok:true};});}
 async readmit(a:Actor,id:string,input:any){allow(a,'patients.manage');uuid.parse(id);const b=z.object({bed_id:optionalId,doctor_id:optionalId}).parse(input);return this.db.tx(async c=>{const p=(await c.query('SELECT status FROM patients WHERE id=$1 FOR UPDATE',[id])).rows[0];if(!p) throw new NotFoundException();if(p.status!=='ARCHIVED') throw new ConflictException('Пацієнт вже активний');await this.validateDoctor(c,b.doctor_id);await this.validateBed(c,b.bed_id);await c.query('INSERT INTO admissions(patient_id,bed_id,doctor_id) VALUES($1,$2,$3)',[id,b.bed_id??null,b.doctor_id??null]);await c.query("UPDATE patients SET status='ACTIVE' WHERE id=$1",[id]);await this.audit(c,a,'patient.readmitted',id);await this.changed(c);return {id};});}
 async note(a:Actor,id:string,input:any){allow(a,'notes.write');uuid.parse(id);const {body}=z.object({body:z.string().trim().min(1).max(20000)}).parse(input);return this.db.tx(async c=>{await this.activePatient(c,id);const n=(await c.query('INSERT INTO medical_notes(patient_id,admission_id,author_id,body) SELECT $1,id,$2,$3 FROM admissions WHERE patient_id=$1 AND discharged_at IS NULL RETURNING id',[id,a.id,body])).rows[0];if(!n) throw new ConflictException('Немає госпіталізації');await this.audit(c,a,'note.created',n.id);return n;});}
 async rooms(a:Actor){allow(a,'rooms.read');return (await this.db.query('SELECT r.id,r.room_number,COALESCE(jsonb_agg(jsonb_build_object(\'id\',b.id,\'bed_number\',b.bed_number,\'qr_uid\',b.qr_uid,\'patient_id\',p.id,\'patient_name\',p.name) ORDER BY b.bed_number) FILTER(WHERE b.id IS NOT NULL),\'[]\') AS beds FROM rooms r LEFT JOIN beds b ON b.room_id=r.id AND b.deleted_at IS NULL LEFT JOIN admissions a ON a.bed_id=b.id AND a.discharged_at IS NULL LEFT JOIN patients p ON p.id=a.patient_id WHERE r.deleted_at IS NULL GROUP BY r.id ORDER BY r.room_number')).rows;}
 async room(a:Actor,input:any){allow(a,'rooms.manage');const b=z.object({room_number:text}).parse(input);return this.db.tx(async c=>{const r=(await c.query('INSERT INTO rooms(room_number) VALUES($1) RETURNING *',[b.room_number])).rows[0];await this.audit(c,a,'room.created',r.id);return r;});}
 async bed(a:Actor,id:string,input:any){allow(a,'rooms.manage');uuid.parse(id);const b=z.object({bed_number:text}).parse(input);return this.db.tx(async c=>{if(!(await c.query('SELECT id FROM rooms WHERE id=$1 AND deleted_at IS NULL FOR UPDATE',[id])).rows.length)throw new ConflictException('Палату видалено або не існує');const r=(await c.query('INSERT INTO beds(room_id,bed_number) VALUES($1,$2) RETURNING *',[id,b.bed_number])).rows[0];await this.audit(c,a,'bed.created',r.id);return r;});}
 async byQr(a:Actor,uid:string){uuid.parse(uid);if(a.role==='NURSE'){allow(a,'qr.read');if(!await this.onShift(a)) throw new ForbiddenException('Почніть зміну');}else allow(a,'patients.read');const b=(await this.db.query('SELECT b.*,r.room_number,a.patient_id FROM beds b JOIN rooms r ON r.id=b.room_id LEFT JOIN admissions a ON a.bed_id=b.id AND a.discharged_at IS NULL WHERE b.qr_uid=$1 AND b.deleted_at IS NULL AND r.deleted_at IS NULL',[uid])).rows[0];if(!b) throw new NotFoundException();return {...b,patient:b.patient_id?await this.patient(a,b.patient_id,true):null};}
 async cabinets(a:Actor){if(!a.permissions.some(p=>['cabinets.manage','tasks.create','appointments.read'].includes(p))) throw new ForbiddenException();return (await this.db.query('SELECT * FROM cabinets ORDER BY name')).rows;}
 async cabinet(a:Actor,input:any){allow(a,'cabinets.manage');const b=z.object({name:text,type:text}).parse(input);return this.db.tx(async c=>{const r=(await c.query('INSERT INTO cabinets(name,type) VALUES($1,$2) RETURNING *',[b.name,b.type])).rows[0];await this.audit(c,a,'cabinet.created',r.id);return r;});}
 async appointments(a:Actor){if(!a.permissions.some(p=>['cabinets.manage','appointments.read'].includes(p))) throw new ForbiddenException();if(a.role==='NURSE'&&!await this.onShift(a)) throw new ForbiddenException('Почніть зміну');return (await this.db.query("SELECT ap.*,p.name AS patient_name,c.name AS cabinet_name FROM appointments ap JOIN patients p ON p.id=ap.patient_id JOIN cabinets c ON c.id=ap.cabinet_id WHERE ap.starts_at>=now()-interval '7 days' ORDER BY ap.starts_at LIMIT 500")).rows;}
 async appointment(a:Actor,input:any){allow(a,'cabinets.manage');const b=z.object({patient_id:uuid,cabinet_id:uuid,starts_at:date,ends_at:date}).parse(input);
  if(new Date(b.ends_at)<=new Date(b.starts_at)) throw new BadRequestException('Кінець має бути після початку');
  return this.db.tx(async c=>{await this.activePatient(c,b.patient_id);
   // Cabinet row mutex + patient row mutex serialize all competing bookings.
   if(!(await c.query('SELECT id FROM cabinets WHERE id=$1 FOR UPDATE',[b.cabinet_id])).rows.length) throw new NotFoundException('Кабінет не знайдено');
   if((await c.query("SELECT 1 FROM appointments WHERE status='BOOKED' AND (cabinet_id=$1 OR patient_id=$2) AND starts_at<$4 AND ends_at>$3",[b.cabinet_id,b.patient_id,b.starts_at,b.ends_at])).rows.length) throw new ConflictException('Кабінет або пацієнт зайнятий у цей час');
   const r=(await c.query('INSERT INTO appointments(patient_id,cabinet_id,starts_at,ends_at,created_by) VALUES($1,$2,$3,$4,$5) RETURNING *',[b.patient_id,b.cabinet_id,b.starts_at,b.ends_at,a.id])).rows[0];await this.audit(c,a,'appointment.created',r.id);await this.changed(c);return r;
  });
 }
 async appointmentStatus(a:Actor,id:string,input:any){allow(a,'appointments.manage');uuid.parse(id);const b=z.object({status:z.enum(['CANCELLED','COMPLETED'])}).parse(input);return this.db.tx(async c=>{const r=(await c.query("UPDATE appointments SET status=$1 WHERE id=$2 AND status='BOOKED' RETURNING id",[b.status,id])).rows[0];if(!r) throw new ConflictException('Запис вже закрито');await this.audit(c,a,'appointment.'+b.status.toLowerCase(),id);await this.changed(c);return r;});}
 async tasks(a:Actor){if(a.role==='NURSE'){allow(a,'tasks.work');if(!await this.onShift(a)) throw new ForbiddenException('Почніть зміну');}else allow(a,'tasks.read');return (await this.db.query("SELECT t.*,p.name AS patient_name,u.name AS nurse_name,c.name AS cabinet_name FROM tasks t JOIN patients p ON p.id=t.patient_id LEFT JOIN users u ON u.id=t.taken_by LEFT JOIN cabinets c ON c.id=t.cabinet_id WHERE ($1::uuid IS NULL OR t.status='OPEN' OR t.taken_by=$1) AND (t.status IN ('OPEN','IN_PROGRESS') OR t.created_at>now()-interval '7 days') ORDER BY t.scheduled_at LIMIT 500",[a.role==='NURSE'?a.id:null])).rows;}
 async task(a:Actor,input:any){allow(a,'tasks.create');const b=z.object({patient_id:uuid,description:z.string().trim().min(1).max(4000),task_type:text,scheduled_at:date,cabinet_id:optionalId}).parse(input);return this.db.tx(async c=>{await this.activePatient(c,b.patient_id);const r=(await c.query('INSERT INTO tasks(patient_id,admission_id,created_by,description,task_type,scheduled_at,cabinet_id) SELECT $1,id,$2,$3,$4,$5,$6 FROM admissions WHERE patient_id=$1 AND discharged_at IS NULL RETURNING *',[b.patient_id,a.id,b.description,b.task_type,b.scheduled_at,b.cabinet_id??null])).rows[0];if(!r) throw new ConflictException('Немає госпіталізації');await this.audit(c,a,'task.created',r.id);await this.changed(c);return r;});}
 async taskAction(a:Actor,id:string,action:string){uuid.parse(id);if(!['claim','complete','release','cancel'].includes(action)) throw new NotFoundException();if(action==='cancel') allow(a,'tasks.create');else allow(a,'tasks.work');
  return this.db.tx(async c=>{
   const t=(await c.query('SELECT patient_id FROM tasks WHERE id=$1',[id])).rows[0];if(!t) throw new NotFoundException();await this.activePatient(c,t.patient_id);
   // User lock prevents ending a shift concurrently with claiming work.
   if(action!=='cancel'){await c.query('SELECT id FROM users WHERE id=$1 FOR UPDATE',[a.id]);if(!await this.onShift(a,c)) throw new ForbiddenException('Немає активної зміни');}
   const queries:Record<string,string>={
    claim:"UPDATE tasks SET status='IN_PROGRESS',taken_by=$2,taken_at=now() WHERE id=$1 AND status='OPEN' AND taken_by IS NULL RETURNING *",
    complete:"UPDATE tasks SET status='COMPLETED',completed_at=now() WHERE id=$1 AND status='IN_PROGRESS' AND taken_by=$2 RETURNING *",
    release:"UPDATE tasks SET status='OPEN',taken_by=NULL,taken_at=NULL WHERE id=$1 AND status='IN_PROGRESS' AND taken_by=$2 RETURNING *",
    cancel:"UPDATE tasks SET status='CANCELLED' WHERE id=$1 AND created_by=$2 AND status IN ('OPEN','IN_PROGRESS') RETURNING *"
   };
   const r=(await c.query(queries[action],[id,a.id])).rows[0];if(!r) throw new ConflictException('Завдання вже змінено або належить іншому працівнику');await this.audit(c,a,'task.'+action,id);await this.changed(c);return r;
  });
 }
 async auditList(a:Actor){allow(a,'audit.read');return (await this.db.query('SELECT e.*,u.name AS actor FROM audit_events e LEFT JOIN users u ON u.id=e.actor_id ORDER BY e.id DESC LIMIT 300')).rows;}
}
