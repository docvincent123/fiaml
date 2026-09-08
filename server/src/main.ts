import 'reflect-metadata';
import {Module} from '@nestjs/common';
import {NestFactory} from '@nestjs/core';
import {Database} from './db';
import {Clinic} from './service';
import {ApiController,PublicController,AuthGuard,CLINIC,Errors} from './http';
import {Server} from 'socket.io';
import helmet from 'helmet';
import express from 'express';
import {existsSync} from 'node:fs';
import {resolve} from 'node:path';
const db=new Database();const clinic=new Clinic(db);
@Module({controllers:[ApiController,PublicController],providers:[{provide:CLINIC,useValue:clinic},AuthGuard]}) class App{}
async function main(){
 await db.migrate();await clinic.bootstrap();
 const app=await NestFactory.create(App,{logger:['error','warn','log']});
 app.use(helmet({contentSecurityPolicy:{directives:{defaultSrc:["'self'"],scriptSrc:["'self'"],styleSrc:["'self'","'unsafe-inline'"],imgSrc:["'self'",'data:','blob:'],connectSrc:["'self'",'ws:','wss:'],upgradeInsecureRequests:process.env.NODE_ENV==='production'?[]:null}},crossOriginEmbedderPolicy:false}));
 app.use('/api',(_req:any,res:any,next:any)=>{res.setHeader('Cache-Control','no-store');next();});
 app.useGlobalFilters(new Errors());
 // No wildcard CORS. Mobile web is served by this same server / reverse proxy.
 const io=new Server(app.getHttpServer(),{allowRequest:(req,cb)=>{const origin=req.headers.origin;cb(null,!origin||origin===process.env.PUBLIC_URL);}});
 io.use(async(socket,next)=>{try{socket.data.actor=await clinic.authenticate(socket.handshake.auth.token??'');next();}catch{next(new Error('Unauthorized'));}});
 io.on('connection',socket=>{socket.join('changes');});
 let polling=false;
 const timer=setInterval(async()=>{if(polling)return;polling=true;try{
   for(const socket of io.sockets.sockets.values()){try{await clinic.authenticate(socket.handshake.auth.token??'');}catch{socket.disconnect(true);}}
   await db.tx(async c=>{const rows=(await c.query('SELECT id FROM outbox WHERE sent_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED')).rows;if(rows.length){io.to('changes').emit('changed');await c.query('UPDATE outbox SET sent_at=now() WHERE id=ANY($1::bigint[])',[rows.map(r=>r.id)]);}});
 }catch{console.error('Realtime delivery will retry');}finally{polling=false;}},2000);
 timer.unref();
 // Mount static content before Nest registers its terminal 404 handler.
 // The fallback skips /api and /socket.io so Nest still owns API routing.
 const web=resolve(__dirname,'../../web/dist');
 app.use(express.static(web,{index:false}));
 app.use((req:any,res:any,next:any)=>{if(req.method==='GET'&&!req.path.startsWith('/api/')&&!req.path.startsWith('/socket.io')&&existsSync(resolve(web,'index.html')))res.sendFile(resolve(web,'index.html'));else next();});
 await app.listen(Number(process.env.PORT||3000),process.env.HOST||'127.0.0.1');
 const stop=async()=>{clearInterval(timer);io.close();await app.close();await db.pool.end();process.exit(0);};process.on('SIGTERM',stop);process.on('SIGINT',stop);
}
main().catch(e=>{console.error('Startup failed:',e.message);process.exit(1);});
