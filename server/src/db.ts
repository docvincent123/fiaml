import {Pool, type PoolClient} from 'pg';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {AsyncLocalStorage} from 'node:async_hooks';
import {createHash} from 'node:crypto';
import {ConflictException} from '@nestjs/common';
const canonical=(v:any):string=>JSON.stringify(v,(_key,value)=>value&&typeof value==='object'&&!Array.isArray(value)?Object.fromEntries(Object.keys(value).sort().map(k=>[k,value[k]])):value);
export interface Queryable { query(text:string, values?:any[]):Promise<{rows:any[];rowCount?:number|null}> }
export class Database implements Queryable {
  pool:Pool;
  private context=new AsyncLocalStorage<Queryable>();
  inReceipt(){return !!this.context.getStore();}
  async once<T>(actor:string,key:string,scope:string,payload:any,work:()=>Promise<T>):Promise<T>{
    const hash=createHash('sha256').update(scope+'\n'+canonical(payload)).digest('hex');
    return this.tx(async c=>{
      await c.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[actor+':'+key]);
      const old=(await c.query('SELECT payload_hash,response FROM request_receipts WHERE actor_id=$1 AND request_id=$2',[actor,key])).rows[0];
      if(old){if(old.payload_hash!==hash)throw new ConflictException('Операцію вже збережено з іншим вмістом. Перевірте запис перед створенням нового.');return old.response;}
      const result=await this.context.run(c,work);
      await c.query('INSERT INTO request_receipts(actor_id,request_id,payload_hash,response) VALUES($1,$2,$3,$4)',[actor,key,hash,JSON.stringify(result)]);
      return result;
    });
  }
  constructor(url=process.env.DATABASE_URL) {if(!url) throw new Error('DATABASE_URL required');this.pool=new Pool({connectionString:url,max:15});}
  query(text:string,values:any[]=[]){return (this.context.getStore()??this.pool).query(text,values);}
  async tx<T>(fn:(c:Queryable)=>Promise<T>):Promise<T>{const existing=this.context.getStore();if(existing)return fn(existing);const c=await this.pool.connect();try{await c.query('BEGIN');const r=await fn(c);await c.query('COMMIT');return r;}catch(e){await c.query('ROLLBACK');throw e;}finally{c.release();}}
  async migrate(){await this.tx(async c=>{
    await c.query('SELECT pg_advisory_xact_lock(780124)');
    await c.query('CREATE TABLE IF NOT EXISTS schema_migrations(version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())');
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=1')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/001_initial.sql'),'utf8'));
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=2')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/002_care.sql'),'utf8'));
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=3')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/003_room_retirement.sql'),'utf8'));
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=4')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/004_staff_workflow.sql'),'utf8'));
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=5')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/005_patient_numbers.sql'),'utf8'));
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=6')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/006_request_receipts.sql'),'utf8'));
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=7')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/007_delivery_health.sql'),'utf8'));
  });}
}
