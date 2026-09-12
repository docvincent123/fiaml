import {Pool, type PoolClient} from 'pg';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
export interface Queryable { query(text:string, values?:any[]):Promise<{rows:any[];rowCount?:number|null}> }
export class Database implements Queryable {
  pool:Pool;
  constructor(url=process.env.DATABASE_URL) {if(!url) throw new Error('DATABASE_URL required');this.pool=new Pool({connectionString:url,max:15});}
  query(text:string,values:any[]=[]){return this.pool.query(text,values);}
  async tx<T>(fn:(c:Queryable)=>Promise<T>):Promise<T>{const c=await this.pool.connect();try{await c.query('BEGIN');const r=await fn(c);await c.query('COMMIT');return r;}catch(e){await c.query('ROLLBACK');throw e;}finally{c.release();}}
  async migrate(){await this.tx(async c=>{
    await c.query('SELECT pg_advisory_xact_lock(780124)');
    await c.query('CREATE TABLE IF NOT EXISTS schema_migrations(version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())');
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=1')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/001_initial.sql'),'utf8'));
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=2')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/002_care.sql'),'utf8'));
    if(!(await c.query('SELECT 1 FROM schema_migrations WHERE version=3')).rows.length) await c.query(readFileSync(resolve(__dirname,'../sql/003_room_retirement.sql'),'utf8'));
  });}
}
