import {Database} from './db';
const db=new Database();db.migrate().then(()=>db.pool.end()).catch(e=>{console.error(e.message);process.exit(1);});
