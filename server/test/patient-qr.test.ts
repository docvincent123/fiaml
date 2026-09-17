import 'reflect-metadata';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {ApiController} from '../src/http';
test('Patient QR requires permission and an accessible patient; contains no medical fields',async()=>{
 const previous=process.env.PUBLIC_URL;process.env.PUBLIC_URL='https://192.168.1.106';
 const id='12345678-1234-1234-1234-123456789abc';let reads=0;
 const controller=new ApiController({patient:async(_a:any,key:string)=>{reads++;assert.equal(key,id);return {name:'Private name',diagnosis:'Private diagnosis'};}} as any);
 try{
  await assert.rejects(()=>controller.patientQr({actor:{permissions:[]}} as any,id));assert.equal(reads,0);
  const result=await controller.patientQr({actor:{permissions:['patients.manage']}} as any,id);
  assert.equal(result.url,'https://192.168.1.106/patients?patient='+id);
  assert.match(result.svg,/<svg/);assert.ok(!JSON.stringify(result).includes('Private'));
  const denied=new ApiController({patient:async()=>{throw Error('Patient unavailable');}} as any);
  await assert.rejects(()=>denied.patientQr({actor:{permissions:['patients.manage']}} as any,id),/unavailable/);
 }finally{if(previous===undefined)delete process.env.PUBLIC_URL;else process.env.PUBLIC_URL=previous;}
});
