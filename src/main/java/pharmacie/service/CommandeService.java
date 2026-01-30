package pharmacie.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import pharmacie.dao.CommandeRepository;
import pharmacie.dao.DispensaireRepository;
import pharmacie.dao.LigneRepository;
import pharmacie.dao.MedicamentRepository;
import pharmacie.entity.Commande;
import pharmacie.entity.Ligne;
import pharmacie.entity.Medicament;

@Slf4j
@Service
@Validated
public class CommandeService {
    private final CommandeRepository commandeDao;
    private final DispensaireRepository dispensaireDao;
    private final LigneRepository ligneDao;
    private final MedicamentRepository medicamentDao;

    public CommandeService(CommandeRepository commandeDao, DispensaireRepository dispensaireDao, LigneRepository ligneDao, MedicamentRepository medicamentDao) {
        this.commandeDao = commandeDao;
        this.dispensaireDao = dispensaireDao;
        this.ligneDao = ligneDao;
        this.medicamentDao = medicamentDao;
    }

    @Transactional
    public Commande creerCommande(@NonNull String dispensaireCode) {
        log.info("Service : Création d'une commande pour {}", dispensaireCode);
        var dispensaire = dispensaireDao.findById(dispensaireCode).orElseThrow();
        var nouvelleCommande = new Commande(dispensaire);
        nouvelleCommande.setAdresseLivraison(dispensaire.getAdresse());
        
        var nbArticles = dispensaireDao.nombreArticlesCommandesPar(dispensaireCode);
        if (nbArticles > 100) {
            nouvelleCommande.setRemise(new BigDecimal("0.15"));
        }
        commandeDao.save(nouvelleCommande);
        return nouvelleCommande;
    }

    @Transactional
    public Ligne ajouterLigne(int commandeNum, int medicamentRef, @Positive int quantite) {
        log.info("Service : Ajout de {} unités du médicament {} à la commande {}", quantite, medicamentRef, commandeNum);

        // 1. Récupération des entités
        Commande commande = commandeDao.findById(commandeNum).orElseThrow();
        Medicament medicament = medicamentDao.findById(medicamentRef).orElseThrow();

        // 2. Vérification des règles métier
        if (commande.getEnvoyeele() != null) {
            throw new IllegalStateException("Impossible de modifier une commande déjà expédiée.");
        }
        if (medicament.isIndisponible()) {
            throw new IllegalStateException("Le médicament est marqué comme indisponible.");
        }
        
        // Vérification du stock : Stock Physique doit être suffisant pour couvrir (Déjà Réservé + Nouvelle Demande)
        if (medicament.getUnitesEnStock() < (medicament.getUnitesCommandees() + quantite)) {
            throw new IllegalStateException("Stock insuffisant pour satisfaire la demande.");
        }

        // 3. Mise à jour des unités commandées (réservation)
        medicament.setUnitesCommandees(medicament.getUnitesCommandees() + quantite);
        medicamentDao.save(medicament);

        // 4. Création ou mise à jour de la ligne
        Ligne ligne = ligneDao.findByCommandeAndMedicament(commande, medicament)
                .orElse(new Ligne(commande, medicament, 0));
        
        ligne.setQuantite(ligne.getQuantite() + quantite);
        
        // Si c'est une nouvelle ligne, il faut l'ajouter à la liste de la commande (si bidirectionnel géré manuellement)
        // Mais JPA gère souvent ça via le save de la ligne si la relation est correcte.
        
        return ligneDao.save(ligne);
    }

    @Transactional
    public void supprimerLigne(int id) {
        log.info("Service : Suppression de la ligne {}", id);
        
        Ligne ligne = ligneDao.findById(id).orElseThrow();
        Commande commande = ligne.getCommande();
        Medicament medicament = ligne.getMedicament();

        if (commande.getEnvoyeele() != null) {
            throw new IllegalStateException("Impossible de modifier une commande déjà expédiée.");
        }

        // Libération des unités commandées
        medicament.setUnitesCommandees(medicament.getUnitesCommandees() - ligne.getQuantite());
        medicamentDao.save(medicament);

        ligneDao.delete(ligne);
    }

    @Transactional
    public Commande enregistreExpedition(int commandeNum) {
        log.info("Service : Expédition de la commande {}", commandeNum);
        
        Commande commande = commandeDao.findById(commandeNum).orElseThrow();

        if (commande.getEnvoyeele() != null) {
            throw new IllegalStateException("Cette commande a déjà été expédiée.");
        }

        // Marquer comme expédiée
        commande.setEnvoyeele(LocalDate.now());

        // Mettre à jour les stocks physiques et les compteurs de réservation
        for (Ligne ligne : commande.getLignes()) {
            Medicament m = ligne.getMedicament();
            int quantite = ligne.getQuantite();

            // On décrémente le stock physique car ça sort de l'entrepôt
            m.setUnitesEnStock(m.getUnitesEnStock() - quantite);
            // On décrémente les unités commandées car elles ne sont plus "en attente", elles sont parties
            m.setUnitesCommandees(m.getUnitesCommandees() - quantite);
            
            medicamentDao.save(m);
        }

        return commandeDao.save(commande);
    }

    @Transactional
    public Commande getCommande(int commandeNum) {
        return commandeDao.findById(commandeNum).orElseThrow();
    }

    @Transactional
    public List<Commande> getCommandeEnCoursPour(String dispensaireCode) {
        return commandeDao.commandesEnCoursPour(dispensaireCode);
    }
}