package com.beavent.backend.comms.internal;

import com.beavent.backend.platform.SystemDb;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** Who in a business should hear about something: its owners and the active members whose role holds a permission. */
@Component
class OrgPeople {

    private final DSLContext system;

    OrgPeople(@SystemDb DSLContext system) {
        this.system = system;
    }

    /** The business's owners and the active members whose role holds {@code permission}. */
    List<UUID> with(UUID orgId, String permission) {
        return system.resultQuery("""
                        SELECT DISTINCT m.user_id
                          FROM tenant.members m
                          LEFT JOIN tenant.role_permissions rp ON rp.role_id = m.role_id AND rp.permission_code = ?
                         WHERE m.org_id = ? AND m.status = 'ACTIVE' AND (m.is_owner OR rp.permission_code IS NOT NULL)
                        """, permission, orgId).fetch(0, UUID.class);
    }

    /** The people who do the work of an appointment and have their own login. */
    List<UUID> doing(UUID appointmentId) {
        return system.resultQuery("""
                        SELECT DISTINCT m.user_id
                          FROM scheduling.appointment_items i
                          JOIN team.staff s ON s.id = i.staff_id AND s.org_id = i.org_id
                          JOIN tenant.members m ON m.id = s.member_id AND m.org_id = s.org_id AND m.status = 'ACTIVE'
                         WHERE i.appointment_id = ?
                        """, appointmentId).fetch(0, UUID.class);
    }
}
