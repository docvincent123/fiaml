// CI-only fixture serving the real production React bundle to the Windows host.
// Clinical API behavior is tested separately against PostgreSQL, never mocked in production.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
const root=path.resolve('web/dist');
http.createServer((req,res)=>{
 const url=new URL(req.url,'http://127.0.0.1:8787');
 res.setHeader('Cache-Control','no-store');
 if(url.pathname==='/api/health'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({status:'ok',brand:'QureMed Industries'}));return;}
 if(url.pathname.startsWith('/api/')){res.writeHead(401,{'Content-Type':'application/json'});res.end(JSON.stringify({message:'CI fixture has no authenticated session'}));return;}
 const file=url.pathname.startsWith('/assets/')?path.resolve(root,'.'+url.pathname):path.join(root,'index.html');
 if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}
 res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html');fs.createReadStream(file).pipe(res);
}).listen(8787,'127.0.0.1');
