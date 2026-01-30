package pharmacie.service;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import pharmacie.dao.LigneRepository;
import pharmacie.dao.MedicamentRepository;
import pharmacie.entity.Ligne;
import pharmacie.entity.Medicament;

@SpringBootTest
@Transactional
class CommandeServiceTest {

    @Autowired
    private CommandeService service;

    @Autowired
    private MedicamentRepository medicamentDao;

    @Autowired
    private LigneRepository ligneDao;

    // Data from test_data.sql
    static final int COMMANDE_NON_EXPEDIEE = 99998; // Ligne: Med 98, Qty 16
    static final int COMMANDE_EXPEDIEE = 99999;
    static final int MED_DISPO_STOCK_100 = 93;
    static final int MED_INDISPONIBLE = 97;
    static final int MED_STOCK_LIMIT = 98; // Stock 26, Cmd 20. Available 6.

    @Test
    void testAjouterLigneSuccesNouvelleLigne() {
        // Add 5 units of Med 93 to Order 99998
        Ligne ligne = service.ajouterLigne(COMMANDE_NON_EXPEDIEE, MED_DISPO_STOCK_100, 5);
        assertNotNull(ligne.getId());
        assertEquals(5, ligne.getQuantite());
        assertEquals(MED_DISPO_STOCK_100, ligne.getMedicament().getReference());
        assertEquals(COMMANDE_NON_EXPEDIEE, ligne.getCommande().getNumero());

        // Check updates
        Medicament m = medicamentDao.findById(MED_DISPO_STOCK_100).orElseThrow();
        assertEquals(5, m.getUnitesCommandees()); // Was 0
    }

    @Test
    void testAjouterLigneSuccesMiseAJour() {
        // Add 1 unit of Med 98 to Order 99998 (already has 16)
        Ligne ligne = service.ajouterLigne(COMMANDE_NON_EXPEDIEE, MED_STOCK_LIMIT, 1);

        assertEquals(17, ligne.getQuantite()); // 16 + 1

        // Check updates
        Medicament m = medicamentDao.findById(MED_STOCK_LIMIT).orElseThrow();
        assertEquals(21, m.getUnitesCommandees()); // Was 20 (assuming test_data.sql is as read)
    }

    @Test
    void testAjouterLigneStockInsuffisant() {
        // Med 98: Stock 26, Cmd 20. Available 6.
        // Try to add 7.
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
            service.ajouterLigne(COMMANDE_NON_EXPEDIEE, MED_STOCK_LIMIT, 7);
        });
        assertEquals("Stock insuffisant pour satisfaire la demande.", e.getMessage());
    }

    @Test
    void testAjouterLigneMedicamentIndisponible() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
            service.ajouterLigne(COMMANDE_NON_EXPEDIEE, MED_INDISPONIBLE, 1);
        });
        assertEquals("Le médicament est marqué comme indisponible.", e.getMessage());
    }

    @Test
    void testAjouterLigneCommandeExpediee() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
            service.ajouterLigne(COMMANDE_EXPEDIEE, MED_DISPO_STOCK_100, 1);
        });
        assertEquals("Impossible de modifier une commande déjà expédiée.", e.getMessage());
    }

    @Test
    void testSupprimerLigneSucces() {
        // Fetch existing line from Order 99998. It has Med 98.
        var commande = service.getCommande(COMMANDE_NON_EXPEDIEE);
        assertFalse(commande.getLignes().isEmpty());
        // Find line for Med 98
        Ligne ligne = commande.getLignes().stream()
                .filter(l -> l.getMedicament().getReference() == MED_STOCK_LIMIT)
                .findFirst()
                .orElseThrow();

        int lignId = ligne.getId();

        service.supprimerLigne(lignId);

        // Verify removed from DAO
        Optional<Ligne> deletedLigne = ligneDao.findById(lignId);
        assertTrue(deletedLigne.isEmpty());

        // Verify med updated
        Medicament m = medicamentDao.findById(MED_STOCK_LIMIT).orElseThrow();
        // Was 20, removed 16 -> should be 4.
        assertEquals(4, m.getUnitesCommandees());
    }

    @Test
    void testSupprimerLigneCommandeExpediee() {
        var commande = service.getCommande(COMMANDE_EXPEDIEE);
        assertFalse(commande.getLignes().isEmpty());
        Ligne ligne = commande.getLignes().iterator().next();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
            service.supprimerLigne(ligne.getId());
        });
        assertEquals("Impossible de modifier une commande déjà expédiée.", e.getMessage());
    }

    @Test
    void testEnregistreExpeditionSucces() {
        // Ship 99998
        // It has Med 98, Qty 16.
        // Med 98: Stock 26, Cmd 20.

        var commande = service.enregistreExpedition(COMMANDE_NON_EXPEDIEE);

        assertEquals(LocalDate.now(), commande.getEnvoyeele());
        assertEquals("2COM", commande.getDispensaire().getCode());

        // Verify med updates
        Medicament m = medicamentDao.findById(MED_STOCK_LIMIT).orElseThrow();
        // Stock: 26 - 16 = 10
        assertEquals(10, m.getUnitesEnStock());
        // Cmd: 20 - 16 = 4
        assertEquals(4, m.getUnitesCommandees());
    }

    @Test
    void testEnregistreExpeditionDejaExpediee() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
            service.enregistreExpedition(COMMANDE_EXPEDIEE);
        });
        assertEquals("Cette commande a déjà été expédiée.", e.getMessage());
    }
}
