package com.dropbid.payment.controller;

import com.dropbid.payment.model.Payment;
import com.dropbid.payment.service.PaymentService;
import com.dropbid.shared.security.UserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /** GET /payments/{id} */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Payment getPayment(@PathVariable String id) {
        return service.getPayment(id);
    }

    /** GET /payments/auction/{auctionId} */
    @GetMapping("/auction/{auctionId}")
    @PreAuthorize("isAuthenticated()")
    public List<Payment> getByAuction(@PathVariable String auctionId) {
        return service.getByAuctionId(auctionId);
    }

    /** GET /payments/user/{userId} */
    @GetMapping("/user/{userId}")
    @PreAuthorize("isAuthenticated()")
    public List<Payment> getUserPayments(@PathVariable String userId) {
        return service.getUserPayments(userId);
    }

    /** GET /payments/me */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public List<Payment> myPayments(@AuthenticationPrincipal UserPrincipal principal) {
        return service.getUserPayments(principal.userId());
    }
}
