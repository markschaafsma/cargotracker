package net.java.cargotracker.infrastructure.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import net.java.cargotracker.application.util.JsonMoxyConfigurationContextResolver;
import net.java.cargotracker.domain.model.cargo.Itinerary;
import net.java.cargotracker.domain.model.cargo.Leg;
import net.java.cargotracker.domain.model.cargo.RouteSpecification;
import net.java.cargotracker.domain.model.location.LocationRepository;
import net.java.cargotracker.domain.model.location.UnLocode;
import net.java.cargotracker.domain.model.voyage.VoyageNumber;
import net.java.cargotracker.domain.model.voyage.VoyageRepository;
import net.java.cargotracker.domain.service.RoutingService;
import net.java.pathfinder.api.TransitEdge;
import net.java.pathfinder.api.TransitPath;
import org.glassfish.jersey.moxy.json.MoxyJsonFeature;

/**
 * Our end of the routing service. This is basically a data model translation
 * layer between our domain model and the API put forward by the routing team,
 * which operates in a different context from us.
 *
 */
@Stateless
public class ExternalRoutingService implements RoutingService {

    @Resource(lookup = "java:app/configuration/GraphTraversalUrl")
    private String graphTraversalUrl;
    // TODO Can I use injection?
    private final Client jaxrsClient = ClientBuilder.newClient();
    private WebTarget graphTraversalResource;
    @Inject
    private LocationRepository locationRepository;
    @Inject
    private VoyageRepository voyageRepository;
    // TODO Use injection instead?
    private static final Logger log = Logger.getLogger(
            ExternalRoutingService.class.getName());

    @PostConstruct
    public void init() {
        graphTraversalResource = jaxrsClient.target(graphTraversalUrl);
        graphTraversalResource.register(new MoxyJsonFeature()).register(
                new JsonMoxyConfigurationContextResolver());
    }

    @Override
    public List<Itinerary> fetchRoutesForSpecification(
            RouteSpecification routeSpecification) {
        // The RouteSpecification is picked apart and adapted to the external API.
        String origin = routeSpecification.getOrigin().getUnLocode().getIdString();
        String destination = routeSpecification.getDestination().getUnLocode()
                .getIdString();

        List<TransitPath> transitPaths = graphTraversalResource
                .queryParam("origin", origin)
                .queryParam("destination", destination)
//                .request(MediaType.APPLICATION_XML_TYPE)   // Works
                .request(MediaType.APPLICATION_JSON_TYPE)    // Doesn't work - but should.
                // Using:
                //   MediaType.APPLICATION_JSON_TYPE
                // Results in:
                //   Warning: StandardWrapperValve[net.java.cargotracker.application.util.RestConfiguration]:
                //   Servlet.service() for servlet net.java.cargotracker.application.util.RestConfiguration threw exception
                //   java.lang.ClassNotFoundException: javax.xml.parsers.ParserConfigurationException 
                //   not found by org.eclipse.persistence.moxy [228] 
                // Also causes:
                //   org.glassfish.jersey.internal.Error
                //   java.lang.NoClassDefFoundError: Could not initialize class org.eclipse.persistence.jaxb.BeanValidationHelper
                // Issue:
                //   javax.xml.parsers.ParserConfigurationException is part of 'rt.jar' in JDK 7 and 8.
                //   javax.xml.parsers is missing from org.eclipse.persistence.moxy.jar MANIFEST.MF, entry Import-Package
                //   org.eclipse.persistence.moxy.jar is part of EclipseLink
                //   EclipseLink jars implicitly included in the project POM under jersey-media-moxy but with scope provided.
                //   EclipseLink jars included within Glassfish 4.1.2 in folder \glassfish4\glassfish\modules
                //   See: https://bugs.eclipse.org/bugs/show_bug.cgi?id=463169 
                // Solution:
                //   1) Download EclpiseLink 2.6.5 where org.eclipse.persistence.moxy.jar Mainfest.MF has been fixed.
                //   2) Replace old jars with new jars. 10 in total. See:
                //      https://stackoverflow.com/questions/22920319/how-to-change-eclipselink-in-glashfish-4-0/22920766
                //   3) Delete 'glassfish-4\glassfish\domains\domain1\osgi-cache\felix'
                //   4) Restart server and re-test.
                // Notes:
                //   jersey-media-moxy 2.0 uses compile dependency org.eclipse.persistence.moxy 2.5.0-M13.
                //   org.eclipse.persistence.moxy 2.5.0-M13 MANIFEST.MF is incorrect for execution purposes,
                //   but okay for compile purposes.
                //   jersey-media-moxy 2.28 is required for org.eclipse.persistence.moxy-2.6.5 or higher to be included.  
                .get(new GenericType<List<TransitPath>>() {
                });

        // The returned result is then translated back into our domain model.
        List<Itinerary> itineraries = new ArrayList<>();

        for (TransitPath transitPath : transitPaths) {
            Itinerary itinerary = toItinerary(transitPath);
            // Use the specification to safe-guard against invalid itineraries
            if (routeSpecification.isSatisfiedBy(itinerary)) {
                itineraries.add(itinerary);
            } else {
                log.log(Level.FINE,
                        "Received itinerary that did not satisfy the route specification");
            }
        }

        return itineraries;
    }

    private Itinerary toItinerary(TransitPath transitPath) {
        List<Leg> legs = new ArrayList<>(transitPath.getTransitEdges().size());
        for (TransitEdge edge : transitPath.getTransitEdges()) {
            legs.add(toLeg(edge));
        }
        return new Itinerary(legs);
    }

    private Leg toLeg(TransitEdge edge) {
        return new Leg(
                voyageRepository.find(new VoyageNumber(edge.getVoyageNumber())),
                locationRepository.find(new UnLocode(edge.getFromUnLocode())),
                locationRepository.find(new UnLocode(edge.getToUnLocode())),
                edge.getFromDate(), edge.getToDate());
    }
}
