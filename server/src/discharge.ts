import {Controller,Get,Inject,Param,Req,UseGuards} from '@nestjs/common';
import {Care as Clinic} from './care';
import {AuthGuard,CLINIC,type AuthRequest} from './http';

@Controller('api')
@UseGuards(AuthGuard)
export class DischargeController {
  constructor(@Inject(CLINIC) private readonly clinic:Clinic){}

  @Get('patients/:id/discharge-sheet')
  async sheet(@Req() request:AuthRequest,@Param('id') patientId:string){
    // Reuse the normal patient access check first. This also keeps clinical-entry
    // visibility aligned with the role model already used by the patient card.
    const patient:any=await this.clinic.patient(request.actor,patientId);
    const admission=(await this.clinic.db.query(
      `SELECT a.*,u.name AS doctor_name,u.specialty AS doctor_specialty,
              b.bed_number,r.room_number,r.floor
         FROM admissions a
         LEFT JOIN users u ON u.id=a.doctor_id
         LEFT JOIN beds b ON b.id=a.bed_id
         LEFT JOIN rooms r ON r.id=b.room_id
        WHERE a.patient_id=$1
        ORDER BY a.admitted_at DESC
        LIMIT 1`,[patientId])).rows[0];
    if(!admission)return {patient,admission:null,entries:[],performed:[],appointments:[],generated_at:new Date().toISOString()};

    const mayReadTasks=request.actor.permissions.includes('tasks.read')||request.actor.permissions.includes('clinical.write');
    const performed=mayReadTasks?(await this.clinic.db.query(
      `SELECT t.id,t.task_type,t.description,t.medication,t.dose,t.dose_unit,t.route,
              t.completed_at,t.outcome,u.name AS executor
         FROM tasks t
         LEFT JOIN users u ON u.id=t.taken_by
        WHERE t.admission_id=$1
          AND t.status='COMPLETED'
          AND NOT t.not_done
        ORDER BY t.completed_at,t.created_at`,[admission.id])).rows:[];

    const appointments=request.actor.permissions.includes('appointments.read')||request.actor.permissions.includes('cabinets.manage')?(await this.clinic.db.query(
      `SELECT ap.starts_at,ap.ends_at,c.name AS cabinet_name,u.name AS staff_name
         FROM appointments ap
         JOIN cabinets c ON c.id=ap.cabinet_id
         LEFT JOIN users u ON u.id=ap.staff_id
        WHERE ap.patient_id=$1
          AND ap.status='COMPLETED'
          AND ap.starts_at>= $2
          AND ap.starts_at<=COALESCE($3::timestamptz,now())
        ORDER BY ap.starts_at`,[patientId,admission.admitted_at,admission.discharged_at??null])).rows:[];

    const entries=Array.isArray(patient.entries)?patient.entries.filter((entry:any)=>entry.admission_id===admission.id):[];
    return {
      patient:{
        id:patient.id,name:patient.name,birth_date:patient.birth_date,phone:patient.phone,
        address:patient.address,emergency_contact:patient.emergency_contact,status:patient.status
      },
      admission,
      entries,
      performed,
      appointments,
      generated_at:new Date().toISOString()
    };
  }
}
