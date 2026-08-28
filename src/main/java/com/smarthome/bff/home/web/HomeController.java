package com.smarthome.bff.home.web;

import com.smarthome.bff.auth.CurrentUser;
import com.smarthome.bff.home.HomeService;
import com.smarthome.bff.home.web.Dtos.CreateHomeRequest;
import com.smarthome.bff.home.web.Dtos.HomeDto;
import com.smarthome.bff.home.web.Dtos.UpdateHomeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/homes")
public class HomeController {

    private final HomeService service;

    public HomeController(HomeService service) {
        this.service = service;
    }

    @GetMapping
    public List<HomeDto> list(@CurrentUser String user) {
        return service.listHomes(user).stream().map(HomeDto::of).toList();
    }

    @GetMapping("/{id}")
    public HomeDto get(@CurrentUser String user, @PathVariable UUID id) {
        return HomeDto.of(service.getHome(user, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HomeDto create(@CurrentUser String user, @Valid @RequestBody CreateHomeRequest body) {
        return HomeDto.of(service.createHome(user, body.name(), Boolean.TRUE.equals(body.isDefault())));
    }

    @PutMapping("/{id}")
    public HomeDto update(@CurrentUser String user, @PathVariable UUID id,
                          @Valid @RequestBody UpdateHomeRequest body) {
        return HomeDto.of(service.updateHome(user, id, body.name(), body.isDefault()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser String user, @PathVariable UUID id) {
        service.deleteHome(user, id);
    }
}
