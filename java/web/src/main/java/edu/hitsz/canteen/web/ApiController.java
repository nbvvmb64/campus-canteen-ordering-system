package edu.hitsz.canteen.web;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class ApiController {
    private final BusinessService service;
    private final MerchantDashboard dashboard;
    private final SecurityContextRepository contexts;
    private final boolean demo;

    public ApiController(BusinessService service, MerchantDashboard dashboard, SecurityContextRepository contexts,
                         @Value("${canteen.demo.enabled:false}") boolean demo) {
        this.service = service; this.dashboard = dashboard; this.contexts = contexts; this.demo = demo;
    }

    public record Credentials(String username, String password) { }
    public record Register(String username, String password, Role role) { }
    public record DishInput(String dishName, BigDecimal unitPrice, int stock) { }
    public record StatusInput(OrderStatus orderStatus) { }
    public record SaleInput(SaleStatus saleStatus) { }
    public record LineInput(String dishId, int quantity) { }
    public record OrderInput(List<LineInput> lines) { }

    @GetMapping("/auth/csrf")
    public Map<String,String> csrf(CsrfToken csrf) {
        return Map.of("header_name",csrf.getHeaderName(),"token",csrf.getToken(),
                "demo",Boolean.toString(demo));
    }
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,String> register(@RequestBody Register input) {
        if (demo && (input == null || input.role() != Role.STUDENT))
            throw new BusinessException("演示站仅允许学生自助注册");
        User u = service.register(input.username(), chars(input.password()), input.role());
        return userDto(u);
    }
    @PostMapping("/auth/login")
    public Map<String,String> login(@RequestBody Credentials input, HttpServletRequest request,
                                    HttpServletResponse response) {
        User user = service.authenticate(input.username(), chars(input.password()));
        request.getSession(true);
        request.changeSessionId();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user.getUserId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return userDto(user);
    }
    @PostMapping("/auth/logout")
    public Map<String,Boolean> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("ok",true);
    }
    @GetMapping("/auth/me")
    public Map<String,String> me() { return userDto(service.actor(id(),null)); }

    @GetMapping("/dishes")
    public List<Dish> dishes() { return service.availableDishes(id()); }
    @GetMapping("/merchant/dishes")
    public List<Dish> merchantDishes() { return service.merchantDishes(id()); }
    @PostMapping("/merchant/dishes")
    @ResponseStatus(HttpStatus.CREATED)
    public Dish addDish(@RequestBody DishInput input) {
        return service.addDish(id(),input.dishName(),input.unitPrice(),input.stock());
    }
    @PutMapping("/merchant/dishes/{dishId}")
    public Dish updateDish(@PathVariable String dishId,@RequestBody DishInput input) {
        return service.updateDish(id(),dishId,input.dishName(),input.unitPrice(),input.stock());
    }
    @PutMapping("/merchant/dishes/{dishId}/sale-status")
    public Dish saleStatus(@PathVariable String dishId,@RequestBody SaleInput input) {
        return service.saleStatus(id(),dishId,input.saleStatus());
    }
    @DeleteMapping("/merchant/dishes/{dishId}")
    public Dish deleteDish(@PathVariable String dishId) {
        return service.deleteDish(id(),dishId);
    }

    @PostMapping("/orders/preview")
    public BusinessService.Preview preview(@RequestBody OrderInput input) {
        return service.preview(id(),lines(input));
    }
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Order order(@RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
                       @RequestBody OrderInput input) {
        return service.createOrder(id(),requestKey,lines(input));
    }
    @GetMapping("/orders")
    public List<Order> myOrders() { return service.studentOrders(id()); }
    @PostMapping("/orders/{orderId}/cancel")
    public Order cancel(@PathVariable String orderId) { return service.cancel(id(),orderId); }
    @GetMapping("/merchant/orders")
    public List<Order> merchantOrders() { return service.merchantOrders(id()); }
    @PutMapping("/merchant/orders/{orderId}/status")
    public Order merchantStatus(@PathVariable String orderId,@RequestBody StatusInput input) {
        return service.updateOrderStatus(id(),orderId,input.orderStatus());
    }
    @GetMapping("/orders/{orderId}/items")
    public List<OrderItem> orderItems(@PathVariable String orderId) { return service.orderItems(id(),orderId); }
    @GetMapping("/merchants/me/dashboard")
    public Map<String,Object> merchantDashboard() { return dashboard.get(id()); }

    private static char[] chars(String password) { return password == null ? null : password.toCharArray(); }
    private static List<OrderLineRequest> lines(OrderInput input) {
        if (input==null || input.lines()==null) return null;
        return input.lines().stream().map(row -> new OrderLineRequest(row.dishId(),row.quantity())).toList();
    }
    private static Map<String,String> userDto(User u) {
        return Map.of("user_id",u.getUserId(),"username",u.getUsername(),"role",u.getRole().name());
    }
    private static String id() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth==null || !auth.isAuthenticated()) throw new BusinessException("请先登录");
        Object principal = auth.getPrincipal();
        if (principal instanceof String value) return value;
        if (principal instanceof UserDetails details) return details.getUsername();
        throw new BusinessException("登录身份无效");
    }
}
