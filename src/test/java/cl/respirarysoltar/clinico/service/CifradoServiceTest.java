package cl.respirarysoltar.clinico.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CifradoServiceTest {

    private final CifradoService cifradoService = new CifradoService("");

    @Test
    void elContenidoCifradoNoEsTextoPlanoYSeDescifraCorrectamente() {
        String contenido = "El paciente reporta avances en el manejo de la ansiedad.";

        String cifrado = cifradoService.cifrar(contenido);

        assertNotEquals(contenido, cifrado);
        assertEquals(contenido, cifradoService.descifrar(cifrado));
    }

    @Test
    void dosCifradosDelMismoTextoDifierenPorElIvAleatorio() {
        String contenido = "Nota de sesión";

        assertNotEquals(cifradoService.cifrar(contenido), cifradoService.cifrar(contenido));
    }

    @Test
    void enLaNubeNoArrancaSinClave() {
        assertThrows(IllegalStateException.class, () -> new CifradoService("", false));
    }

    @Test
    void rechazaUnaClaveQueNoEsDe32Bytes() {
        String claveCorta = java.util.Base64.getEncoder().encodeToString("corta".getBytes());
        assertThrows(IllegalStateException.class, () -> new CifradoService(claveCorta, false));
    }

    @Test
    void conUnaClaveRealCifraYDescifra() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        CifradoService real = new CifradoService(java.util.Base64.getEncoder().encodeToString(bytes), false);
        assertEquals("nota", real.descifrar(real.cifrar("nota")));
    }
}
