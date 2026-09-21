package cl.zona_ti.compra_service.Model;

// Copia deliberada de auth-service/Model/Alcance.java -- cada servicio
// tiene su propia base de datos y su propio codigo (database-per-service),
// asi que no se comparten clases entre los 2 modulos Maven. Los valores
// tienen que coincidir con los que auth-service mete en el claim "alcance"
// del JWT (ver JwtUtil.generateToken alli).
public enum Alcance {
    GLOBAL,
    EMPRESA,
    USUARIO
}
