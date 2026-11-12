package edu.dau.practolite.common.model;

/**
 * Attribute names of the four DynamoDB tables: the shared data contract between services.
 * The key schemas here must match infra/template.yaml and scripts/local_tables.py.
 */
public final class Schema {

    private Schema() {
    }

    /** Users table. PK userId (= Cognito sub). */
    public static final class Users {
        public static final String USER_ID = "userId";
        public static final String EMAIL = "email";
        public static final String NAME = "name";
        public static final String PHONE = "phone";
        public static final String ROLE = "role";
        public static final String CREATED_AT = "createdAt";
        public static final String UPDATED_AT = "updatedAt";

        private Users() {
        }
    }

    /** Doctors table. PK doctorId (= the doctor's userId). Sparse GSI search-index (searchCity, specKey). */
    public static final class Doctors {
        public static final String DOCTOR_ID = "doctorId";
        public static final String NAME = "name";
        public static final String NAME_LOWER = "nameLower";
        public static final String SPECIALITY = "speciality";
        public static final String CITY = "city";
        public static final String FEE = "fee";
        public static final String CLINIC_NAME = "clinicName";
        public static final String EXPERIENCE_YEARS = "experienceYears";
        public static final String BIO = "bio";
        public static final String VERIFIED = "verified";
        public static final String CREATED_AT = "createdAt";
        public static final String UPDATED_AT = "updatedAt";

        private Doctors() {
        }
    }

    /** Slots table. PK doctorId, SK slotStart (UTC ISO-8601). */
    public static final class Slots {
        public static final String DOCTOR_ID = "doctorId";
        public static final String SLOT_START = "slotStart";
        public static final String STATUS = "status";
        public static final String APPOINTMENT_ID = "appointmentId";
        public static final String DURATION_MIN = "durationMin";
        public static final String CREATED_AT = "createdAt";
        public static final int DURATION_MINUTES = 30;

        private Slots() {
        }
    }

    /** Appointments table. PK appointmentId. GSIs patient-index (patientId, slotStart), doctor-index (doctorId, slotStart). */
    public static final class Appointments {
        public static final String APPOINTMENT_ID = "appointmentId";
        public static final String PATIENT_ID = "patientId";
        public static final String DOCTOR_ID = "doctorId";
        public static final String SLOT_START = "slotStart";
        public static final String STATUS = "status";
        public static final String REASON = "reason";
        public static final String DOCTOR_NAME = "doctorName";
        public static final String PATIENT_NAME = "patientName";
        public static final String CANCELLED_BY = "cancelledBy";
        public static final String CREATED_AT = "createdAt";
        public static final String UPDATED_AT = "updatedAt";
        public static final String PATIENT_INDEX = "patient-index";
        public static final String DOCTOR_INDEX = "doctor-index";

        private Appointments() {
        }
    }
}
