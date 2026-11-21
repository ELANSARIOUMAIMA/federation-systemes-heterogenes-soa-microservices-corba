package ma.mobility.railbridge.corba;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import ma.mobility.railbridge.api.BridgeException;
import mobility.rail.NotAvailable;
import mobility.rail.RailBooking;
import mobility.rail.RailBookingHelper;
import mobility.rail.Trip;
import org.omg.CORBA.ORB;
import org.omg.CORBA.SystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Seul endroit du projet qui "voit" du CORBA. */
@Component
public class RailCorbaClient implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RailCorbaClient.class);

    @FunctionalInterface
    private interface CorbaCall<T> {
        T run(RailBooking stub) throws NotAvailable;
    }

    private final Path iorPath;
    private final ORB orb;
    private RailBooking stub;

    public RailCorbaClient(@Value("${rail.ior.path}") String iorPath) {
        this.iorPath = Paths.get(iorPath);
        Properties props = new Properties();
        props.setProperty("org.omg.CORBA.ORBClass", "org.jacorb.orb.ORB");
        props.setProperty("org.omg.CORBA.ORBSingletonClass", "org.jacorb.orb.ORBSingleton");
        props.setProperty("jacorb.connection.client.connect_timeout", "5000");
        props.setProperty("jacorb.connection.client.pending_reply_timeout", "10000");
        this.orb = ORB.init(new String[0], props);
    }

    public List<Trip> search(String origin, String destination, String date) {
        Trip[] trips = this.<Trip[]>call(true, s -> s.search(origin, destination, date));
        return Arrays.asList(trips);
    }

    /** Non rejoué automatiquement (non idempotent). */
    public String hold(String tripId, int seats) {
        return this.<String>call(false, s -> s.hold(tripId, seats));
    }

    /** Non rejoué automatiquement (non idempotent). */
    public String confirm(String holdId) {
        return this.<String>call(false, s -> s.confirm(holdId));
    }

    /** Idempotent côté opérateur : accepte un holdId ou un bookingRef, jamais d'erreur si inconnu. */
    public void cancel(String reference) {
        this.<Object>call(true, s -> {
            s.cancel(reference);
            return null;
        });
    }

    private <T> T call(boolean idempotent, CorbaCall<T> op) {
        int attempts = idempotent ? 2 : 1;
        SystemException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return op.run(stub());
            } catch (NotAvailable e) {
                throw BridgeException.notAvailable(e.reason);
            } catch (SystemException e) {
                log.warn("Échec CORBA ({}), tentative {}/{} : {}", e.getClass().getSimpleName(), i + 1, attempts, e.getMessage());
                reset();
                last = e;
            }
        }
        throw BridgeException.upstreamUnavailable(
                "Opérateur ferroviaire injoignable : " + (last == null ? "?" : last.getClass().getSimpleName()));
    }

    /** Lit l'IOR à la demande : le bridge n'a pas besoin que rail soit prêt au démarrage. */
    private synchronized RailBooking stub() {
        if (stub == null) {
            String ior;
            try {
                ior = new String(Files.readAllBytes(iorPath), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw BridgeException.upstreamUnavailable(
                        "IOR introuvable (" + iorPath + ") : l'opérateur ferroviaire est-il démarré ?");
            }
            if (ior.isEmpty()) {
                throw BridgeException.upstreamUnavailable("IOR vide (" + iorPath + ")");
            }
            stub = RailBookingHelper.narrow(orb.string_to_object(ior));
            log.info("Référence CORBA RailBooking obtenue depuis {}", iorPath);
        }
        return stub;
    }

    /** Oublie la référence : la prochaine requête relira l'IOR (rail a pu redémarrer, son IP changer). */
    private synchronized void reset() {
        stub = null;
    }

    @Override
    public void destroy() {
        orb.shutdown(true);
    }
}