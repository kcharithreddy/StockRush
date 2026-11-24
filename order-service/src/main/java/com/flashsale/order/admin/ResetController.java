package com.flashsale.order.admin;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST ONLY: deletes a sale's orders. The endpoint does not exist at all unless
 * flashsale.admin.reset-enabled=true (RESET_ENABLED in .env), so a production deploy returns 404.
 */
@RestController
@RequestMapping("/admin/sales")
@ConditionalOnProperty(name = "flashsale.admin.reset-enabled", havingValue = "true")
public class ResetController {

    private final SaleAdminService admin;

    public ResetController(SaleAdminService admin) {
        this.admin = admin;
    }

    @PostMapping("/{id}/reset")
    public WarmupResponse reset(@PathVariable @Positive long id) {
        return admin.reset(id);
    }
}
