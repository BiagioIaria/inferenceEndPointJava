package org.example.inferenceendpoint;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.update.UpdateExecutionFactory;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateProcessor;
import org.apache.jena.update.UpdateRequest;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.HermiT.ReasonerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.*;
import java.util.Set;

@RestController
@RequestMapping("/api/inference")
public class SWRLInferenceController {

    private static final String GRAPHDB_ENDPOINT = "http://localhost:7200/repositories/emoStory";

    @CrossOrigin(origins = {"http://emostory.altervista.org", "http://localhost:3000", "https://emostory.altervista.org"})
    @GetMapping("/run")
    public String runInference() {
        try {
            // 1. Esporta tutte le triple da GraphDB in un file RDF
            exportTriplesToFile(GRAPHDB_ENDPOINT, "exported.rdf");

            // Crea un gestore dell'ontologia
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

            // Percorso al file RDF nella cartella resources
            File rdfFile = new File("exported.rdf");
            IRI documentIRI = IRI.create(rdfFile);

            // Carica l'ontologia (RDF)
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(documentIRI);

            // Rimuovi gli assiomi problematici
            removeProblematicAxioms(manager, ontology);

            // Crea un reasoner factory per HermiT
            OWLReasonerFactory reasonerFactory = new ReasonerFactory();
            OWLReasoner reasoner = reasonerFactory.createReasoner(ontology);

            // Fai inferenze con il reasoner
            reasoner.precomputeInferences();

            // Aggiungi le inferenze all'ontologia
            addInferredAxiomsToOntology(manager, ontology, reasoner);

            // Percorso del file di output per salvare l'ontologia inferita
            File inferredOntologyFile = new File("inferred_ontology.rdf");

            // Salva l'ontologia inferita in un nuovo file RDF
            manager.saveOntology(ontology, IRI.create(inferredOntologyFile.toURI()));

            // Chiudi il reasoner
            reasoner.dispose();

            // 3. Carica il modello RDF in Apache Jena
            Model model = ModelFactory.createDefaultModel();
            model.read("inferred_ontology.rdf");

            // 4. Esegui una query SPARQL per ottenere gli individuals di tipo Agent
            String sparqlQueryString = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
                    "PREFIX : <http://www.purl.org/drammar#>\n" +
                    "\n" +
                    "SELECT (STRAFTER(STR(?individuo), \"#\") AS ?i) ?emo\n" +
                    "WHERE {\n" +
                    "  ?individuo rdf:type :Agent .\n" +
                    "  ?individuo :feels ?emo .\n" +
                    "}";

            Query query = QueryFactory.create(sparqlQueryString);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();

                // 5. Converti i risultati della query in JSON
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                ResultSetFormatter.outputAsJSON(outputStream, results);

                return outputStream.toString("UTF-8");
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "Errore durante l'esecuzione delle inferenze: " + e.getMessage();
        }
    }

    @CrossOrigin(origins = {"http://emostory.altervista.org", "http://localhost:3000", "https://emostory.altervista.org"})
    @GetMapping("/export")
    public void exportRDF(HttpServletResponse response) {
        try {
            // Esporta tutte le triple da GraphDB in un file RDF
            String fileName = "exported.rdf";
            exportTriplesToFile(GRAPHDB_ENDPOINT, fileName);

            // Imposta le intestazioni della risposta HTTP
            response.setContentType("application/rdf+xml");
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);

            // Scrivi il contenuto del file RDF nella risposta HTTP
            try (InputStream inputStream = new FileInputStream(fileName);
                 OutputStream outputStream = response.getOutputStream()) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void removeProblematicAxioms(OWLOntologyManager manager, OWLOntology ontology) {
        OWLDataFactory dataFactory = manager.getOWLDataFactory();
        OWLDataProperty topDataProperty = dataFactory.getOWLTopDataProperty();

        // Trova e rimuovi gli assiomi SubDataPropertyOf problematici
        for (OWLAxiom axiom : ontology.getAxioms()) {
            if (axiom instanceof OWLSubDataPropertyOfAxiom) {
                OWLSubDataPropertyOfAxiom subDataPropertyAxiom = (OWLSubDataPropertyOfAxiom) axiom;
                if (subDataPropertyAxiom.getSuperProperty().equals(topDataProperty)) {
                    manager.removeAxiom(ontology, subDataPropertyAxiom);
                }
            } else if (axiom instanceof OWLDataPropertyAssertionAxiom) {
                OWLDataPropertyAssertionAxiom dataPropertyAssertionAxiom = (OWLDataPropertyAssertionAxiom) axiom;
                if (dataPropertyAssertionAxiom.getProperty().equals(topDataProperty)) {
                    manager.removeAxiom(ontology, dataPropertyAssertionAxiom);
                }
            }
        }
    }

    private void addInferredAxiomsToOntology(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner) {
        // Ottenere l'OWLDataFactory
        OWLDataFactory dataFactory = manager.getOWLDataFactory();

        // Escludi owl:topObjectProperty e owl:topDataProperty
        OWLObjectProperty topObjectProperty = dataFactory.getOWLTopObjectProperty();
        OWLDataProperty topDataProperty = dataFactory.getOWLTopDataProperty();

        // Aggiungi le inferenze sulle sotto-classi
        for (OWLClass cls : ontology.getClassesInSignature()) {
            NodeSet<OWLClass> subClasses = reasoner.getSubClasses(cls, true);
            for (OWLClass subClass : subClasses.getFlattened()) {
                OWLAxiom axiom = dataFactory.getOWLSubClassOfAxiom(subClass, cls);
                manager.addAxiom(ontology, axiom);
            }
        }

        // Aggiungi le inferenze sui tipi di classe (Class assertions)
        for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
            NodeSet<OWLClass> types = reasoner.getTypes(individual, true);
            for (OWLClass type : types.getFlattened()) {
                OWLAxiom axiom = dataFactory.getOWLClassAssertionAxiom(type, individual);
                manager.addAxiom(ontology, axiom);
            }
        }

        // Aggiungi le inferenze sulle proprietà oggetto (Object property assertions)
        for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
            for (OWLObjectProperty objectProperty : ontology.getObjectPropertiesInSignature()) {
                if (!objectProperty.equals(topObjectProperty)) { // Escludi owl:topObjectProperty
                    NodeSet<OWLNamedIndividual> values = reasoner.getObjectPropertyValues(individual, objectProperty);
                    for (OWLNamedIndividual value : values.getFlattened()) {
                        OWLAxiom axiom = dataFactory.getOWLObjectPropertyAssertionAxiom(objectProperty, individual, value);
                        manager.addAxiom(ontology, axiom);
                    }
                }
            }
        }

        // Aggiungi le inferenze sulle proprietà di dati (Data property assertions)
        for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
            for (OWLDataProperty dataProperty : ontology.getDataPropertiesInSignature()) {
                if (!dataProperty.equals(topDataProperty)) { // Escludi owl:topDataProperty
                    Set<OWLLiteral> values = reasoner.getDataPropertyValues(individual, dataProperty);
                    for (OWLLiteral value : values) {
                        OWLAxiom axiom = dataFactory.getOWLDataPropertyAssertionAxiom(dataProperty, individual, value);
                        manager.addAxiom(ontology, axiom);
                    }
                }
            }
        }
    }

    private void exportTriplesToFile(String endpoint, String fileName) throws IOException {
        // Costruisci una query SPARQL per recuperare tutte le triple
        String queryString = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }";

        // Crea la query
        Query query = QueryFactory.create(queryString);

        // Esegui la query
        try (QueryExecution qexec = QueryExecutionFactory.sparqlService(endpoint, query)) {
            Model model = qexec.execConstruct();

            // Scrivi il modello RDF in un file
            try (OutputStream out = new FileOutputStream(fileName)) {
                model.write(out, Lang.RDFXML.getName());
            }
        }
    }

    private void loadRDFToGraphDB(String endpoint, String fileName) throws IOException {
        // Carica il modello RDF dal file
        Model model = RDFDataMgr.loadModel(fileName, Lang.RDFXML);

        // Configura l'endpoint di inserimento dei dati
        String updateEndpoint = endpoint + "/statements";

        // Carica i dati nel repository GraphDB
        try (StringWriter writer = new StringWriter()) {
            // Scrivi il modello RDF in formato Turtle senza prefissi
            RDFDataMgr.write(writer, model, Lang.NTRIPLES);
            String rdfData = writer.toString();

            String updateQuery = "INSERT DATA { " + rdfData + " } ";
            UpdateRequest updateRequest = UpdateFactory.create(updateQuery);
            UpdateProcessor updateProcessor = UpdateExecutionFactory.createRemote(updateRequest, updateEndpoint);
            updateProcessor.execute();
        }
    }
}

