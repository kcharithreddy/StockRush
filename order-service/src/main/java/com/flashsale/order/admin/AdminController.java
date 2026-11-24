package com.flashsale.order.admin;

import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoints. Not authenticated yet: until Step B (Keycloak, admin role) they must only be
 * reachable from the host or the Docker network, never published publicly.
 */
@RestController
@RequestMapping("/admin/sales")
public class AdminController {

    private final SaleAdminService admin;

    public AdminController(SaleAdminService admin) {
        this.admin = admin;
    }

    @PostMapping("/{id}/warmup")
    public WarmupResponse warmup(@PathVariable @Positive long id) {
        return admin.warmup(id);
    }
}
