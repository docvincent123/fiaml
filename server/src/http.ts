import {Body,CanActivate,Catch,Controller,Delete,ExecutionContext,ExceptionFilter,Get,HttpException,Inject,Param,Patch,Post,Query,Req,Res,UseGuards} from '@nestjs/common';
import type {Request,Response} from 'express';
import {ZodError} from 'zod';
import {Clinic} from './service';
import {type Actor,allow,defaults} from './access';
import QRCode from 'qrcode';
export const CLINIC='CLINIC';
export type AuthRequest=Request&{actor:Actor};
export class AuthGuard implements CanActivate {
 constructor(@Inject(CLINIC) private clinic:Clinic){}
 async canActivate(ctx:ExecutionContext){const req=ctx.switchToHttp().getRequest<AuthRequest>();req.actor=await this.clinic.authenticate(req.headers.authorization?.replace(/^Bearer /i,'')??'');return true;}
}
@Catch()
export class Errors implements ExceptionFilter {
 catch(error:any,host:any){const res=host.switchToHttp().getResponse() as Response;
  if(error instanceof ZodError){res.status(400).json({message:'Перевірте поля форми',fields:error.issues.map(i=>({field:i.path.join('.'),message:i.message}))});return;}
  if(error instanceof HttpException){res.status(error.getStatus()).json({message:error.message});return;}
  if(['23505','23P01','23503','23514','22P02','22007','22008'].includes(error.code)){res.status(error.code==='23505'||error.code==='23P01'?409:400).json({message:error.code==='23505'?'Запис вже існує або ліжко зайняте':'Некоректні дані чи пов’язаний запис'});return;}
  console.error('Request failed',error.code??error.name);res.status(500).json({message:'Помилка сервера. Спробуйте ще раз'});
 }
}
@Controller('api')
export class PublicController {
 constructor(@Inject(CLINIC) private clinic:Clinic){}
 @Get('health') async health(){await this.clinic.db.query('SELECT 1');return {status:'ok',brand:'QureMed Industries'};}
 @Post('auth/login') login(@Body() b:any,@Req() r:Request){return this.clinic.login(b,r.ip??'unknown');}
}
@Controller('api') @UseGuards(AuthGuard)
export class ApiController {
 constructor(@Inject(CLINIC) private c:Clinic){}
 @Get('auth/me') me(@Req() r:AuthRequest){return this.c.me(r.actor);}
 @Post('auth/logout') logout(@Req() r:AuthRequest){return this.c.logout(r.actor);}
 @Post('auth/password') password(@Req() r:AuthRequest,@Body() b:any){return this.c.changePassword(r.actor,b);}
 @Get('dashboard') dashboard(@Req() r:AuthRequest){return this.c.dashboard(r.actor);}
 @Get('users') users(@Req() r:AuthRequest){return this.c.users(r.actor);}
 @Get('staff') staff(@Req() r:AuthRequest){return this.c.staff(r.actor);}
 @Get('permissions') permissions(@Req() r:AuthRequest){allow(r.actor,'users.manage');return defaults;}
 @Post('users') user(@Req() r:AuthRequest,@Body() b:any){return this.c.saveUser(r.actor,b);}
 @Patch('users/:id') updateUser(@Req() r:AuthRequest,@Param('id') id:string,@Body() b:any){return this.c.saveUser(r.actor,b,id);}
 @Get('sessions') sessions(@Req() r:AuthRequest){return this.c.sessions(r.actor);}
 @Delete('sessions/:id') revoke(@Req() r:AuthRequest,@Param('id') id:string){return this.c.revoke(r.actor,id);}
 @Post('shift') shift(@Req() r:AuthRequest,@Body() b:any){return this.c.shift(r.actor,b);}
 @Get('patients') patients(@Req() r:AuthRequest,@Query() q:any){return this.c.patients(r.actor,q);}
 @Post('patients') register(@Req() r:AuthRequest,@Body() b:any){return this.c.register(r.actor,b);}
 @Get('patients/:id') patient(@Req() r:AuthRequest,@Param('id') id:string){return this.c.patient(r.actor,id);}
 @Patch('patients/:id') edit(@Req() r:AuthRequest,@Param('id') id:string,@Body() b:any){return this.c.editPatient(r.actor,id,b);}
 @Post('patients/:id/discharge') discharge(@Req() r:AuthRequest,@Param('id') id:string){return this.c.discharge(r.actor,id);}
 @Post('patients/:id/readmit') readmit(@Req() r:AuthRequest,@Param('id') id:string,@Body() b:any){return this.c.readmit(r.actor,id,b);}
 @Post('patients/:id/notes') note(@Req() r:AuthRequest,@Param('id') id:string,@Body() b:any){return this.c.note(r.actor,id,b);}
 @Get('rooms') rooms(@Req() r:AuthRequest){return this.c.rooms(r.actor);}
 @Post('rooms') room(@Req() r:AuthRequest,@Body() b:any){return this.c.room(r.actor,b);}
 @Post('rooms/:id/beds') bed(@Req() r:AuthRequest,@Param('id') id:string,@Body() b:any){return this.c.bed(r.actor,id,b);}
 @Get('beds/qr/:uid') qr(@Req() r:AuthRequest,@Param('uid') id:string){return this.c.byQr(r.actor,id);}
 @Get('beds/qr/:uid/image') async qrImage(@Req() r:AuthRequest,@Param('uid') id:string,@Res() res:Response){allow(r.actor,'rooms.manage');await this.c.byQr(r.actor,id);const base=process.env.PUBLIC_URL;if(!base) throw new HttpException('Налаштуйте PUBLIC_URL сервера',503);res.type('image/svg+xml').send(await QRCode.toString(new URL('/bed/'+id,base).href,{type:'svg',margin:2}));}
 @Get('cabinets') cabinets(@Req() r:AuthRequest){return this.c.cabinets(r.actor);}
 @Post('cabinets') cabinet(@Req() r:AuthRequest,@Body() b:any){return this.c.cabinet(r.actor,b);}
 @Get('appointments') appointments(@Req() r:AuthRequest){return this.c.appointments(r.actor);}
 @Post('appointments') appointment(@Req() r:AuthRequest,@Body() b:any){return this.c.appointment(r.actor,b);}
 @Patch('appointments/:id') appointmentStatus(@Req() r:AuthRequest,@Param('id') id:string,@Body() b:any){return this.c.appointmentStatus(r.actor,id,b);}
 @Get('tasks') tasks(@Req() r:AuthRequest){return this.c.tasks(r.actor);}
 @Post('tasks') task(@Req() r:AuthRequest,@Body() b:any){return this.c.task(r.actor,b);}
 @Post('tasks/:id/:action') action(@Req() r:AuthRequest,@Param('id') id:string,@Param('action') action:string){return this.c.taskAction(r.actor,id,action);}
 @Get('audit') audit(@Req() r:AuthRequest){return this.c.auditList(r.actor);}
}
