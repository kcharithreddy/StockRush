package com.flashsale.order.admin;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/sales")
public class AdminController {

    private final SaleAdminService admin;

    public AdminController(SaleAdminService admin) {
        this.admin = admin;
    }

    @PostMapping("/{id}/warmup")
    public WarmupResponse warmup(@PathVariable long id) {
        return admin.warmup(id);
    }

    @PostMapping("/{id}/reset")
    public WarmupResponse reset(@PathVariable long id) {
        return admin.reset(id);
    }
}
