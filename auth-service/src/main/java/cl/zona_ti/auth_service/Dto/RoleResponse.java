package cl.zona_ti.auth_service.Dto;

import cl.zona_ti.auth_service.Model.Alcance;

public record RoleResponse(Long id, String nombre, Alcance alcance, String descripcion) {
}
