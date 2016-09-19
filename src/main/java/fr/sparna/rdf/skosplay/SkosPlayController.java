package fr.sparna.rdf.skosplay;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.security.InvalidParameterException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.List;
import java.util.ResourceBundle;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openrdf.model.Literal;
import org.openrdf.model.Value;
import org.openrdf.model.vocabulary.DC;
import org.openrdf.model.vocabulary.DCTERMS;
import org.openrdf.query.BindingSet;
import org.openrdf.query.TupleQueryResultHandlerBase;
import org.openrdf.query.TupleQueryResultHandlerException;
import org.openrdf.repository.Repository;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.Rio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import fr.sparna.commons.io.ReadWriteTextFile;
import fr.sparna.commons.tree.GenericTree;
import fr.sparna.commons.tree.GenericTreeNode;
import fr.sparna.i18n.StrictResourceBundleControl;
import fr.sparna.rdf.sesame.toolkit.languages.Languages.Language;
import fr.sparna.rdf.sesame.toolkit.query.Perform;
import fr.sparna.rdf.sesame.toolkit.query.SelectSparqlHelper;
import fr.sparna.rdf.sesame.toolkit.query.SparqlPerformException;
import fr.sparna.rdf.sesame.toolkit.query.SparqlQuery;
import fr.sparna.rdf.sesame.toolkit.query.SparqlUpdate;
import fr.sparna.rdf.sesame.toolkit.query.builder.SparqlQueryBuilder;
import fr.sparna.rdf.sesame.toolkit.repository.LocalMemoryRepositoryFactory;
import fr.sparna.rdf.sesame.toolkit.repository.LocalMemoryRepositoryFactory.FactoryConfiguration;
import fr.sparna.rdf.sesame.toolkit.repository.RepositoryBuilder;
import fr.sparna.rdf.sesame.toolkit.repository.RepositoryFactoryException;
import fr.sparna.rdf.sesame.toolkit.repository.StringRepositoryFactory;
import fr.sparna.rdf.sesame.toolkit.repository.operation.ApplyUpdates;
import fr.sparna.rdf.sesame.toolkit.repository.operation.LoadFromFileOrDirectory;
import fr.sparna.rdf.sesame.toolkit.repository.operation.LoadFromStream;
import fr.sparna.rdf.sesame.toolkit.repository.operation.LoadFromUrl;
import fr.sparna.rdf.sesame.toolkit.repository.operation.RepositoryOperationException;
import fr.sparna.rdf.sesame.toolkit.util.LabelReader;
import fr.sparna.rdf.skos.printer.DisplayPrinter;
import fr.sparna.rdf.skos.printer.autocomplete.Items;
import fr.sparna.rdf.skos.printer.autocomplete.JSONWriter;
import fr.sparna.rdf.skos.printer.reader.AbstractKosDisplayGenerator;
import fr.sparna.rdf.skos.printer.reader.AlignmentDataHarvesterCachedLoader;
import fr.sparna.rdf.skos.printer.reader.AlignmentDataHarvesterIfc;
import fr.sparna.rdf.skos.printer.reader.AlignmentDisplayGenerator;
import fr.sparna.rdf.skos.printer.reader.AlphaIndexDisplayGenerator;
import fr.sparna.rdf.skos.printer.reader.AutocompleteItemsReader;
import fr.sparna.rdf.skos.printer.reader.BodyReader;
import fr.sparna.rdf.skos.printer.reader.ConceptBlockReader;
import fr.sparna.rdf.skos.printer.reader.ConceptListDisplayGenerator;
import fr.sparna.rdf.skos.printer.reader.HeaderAndFooterReader;
import fr.sparna.rdf.skos.printer.reader.HierarchicalDisplayGenerator;
import fr.sparna.rdf.skos.printer.reader.IndexGenerator;
import fr.sparna.rdf.skos.printer.reader.IndexGenerator.IndexType;
import fr.sparna.rdf.skos.printer.reader.TranslationTableDisplayGenerator;
import fr.sparna.rdf.skos.printer.reader.TranslationTableReverseDisplayGenerator;
import fr.sparna.rdf.skos.printer.schema.KosDocument;
import fr.sparna.rdf.skos.toolkit.JsonSKOSTreePrinter;
import fr.sparna.rdf.skos.toolkit.SKOSRules;
import fr.sparna.rdf.skos.toolkit.SKOSTreeBuilder;
import fr.sparna.rdf.skos.toolkit.SKOSTreeNode;
import fr.sparna.rdf.skos.toolkit.SKOSTreeNode.NodeType;

/**
 * The main entry point.
 * @Controller indicates this class will be the application controller, the main entry point.
 *
 * To add an extra RequestMapping here, add the corresponding path to web.xml mappings.
 *
 * @author Thomas Francart
 *
 */
@Controller
public class SkosPlayController {

	private Logger log = LoggerFactory.getLogger(this.getClass().getName());

	@Autowired
	protected ServletContext servletContext;

	private enum SOURCE_TYPE {
		FILE,
		URL,
		EXAMPLE
	}

	@RequestMapping("/home")
	public ModelAndView home(HttpServletRequest request) {

		// String url = "http://cdmk-caribbean.net:3030/cdmk/sparql";
		String url = "https://cdmk.poolparty.biz/PoolParty/sparql/cdmk";
		// we are loading an RDF file from the web, use the localRepositoryBuilder and apply inference if required

		RepositoryBuilder localRepositoryBuilder = new RepositoryBuilder();
		Repository repository;
		boolean rdfsInference = false;
		boolean owl2skos = false;
		boolean skosxl2skos = false;

		// URI scheme = URI.create("http://cdmk-caribbean.net:8010/skosmos/vocab/");
		// URI scheme = URI.create("http://cdmk.poolparty.biz/CDMK");

		localRepositoryBuilder.addOperation(new LoadFromFileOrDirectory("skos.rdf"));

		try {
			if(!StringRepositoryFactory.isEndpointURL(url)) {

					try {
						localRepositoryBuilder.addOperation(new LoadFromUrl(new URL(url)));
					} catch( Exception e) {
						log.info("Malformed URL");
					}
					repository = localRepositoryBuilder.createNewRepository();

					// apply OWL2SKOS rules if needed
					try {
						if(owl2skos && !StringRepositoryFactory.isEndpointURL(url)) {
							// apply inference
							ApplyUpdates au = new ApplyUpdates(SparqlUpdate.fromUpdateList(SKOSRules.getOWL2SKOSRuleset()));
							au.execute(repository);
						}
					} catch (RepositoryOperationException e1) {
						return doError(request, e1.getMessage());
					}
				} else {
					// this is a endpoint
					repository = RepositoryBuilder.fromString(url, rdfsInference);
				}
			} catch (RepositoryFactoryException e) {
			return doError(request, e);
		}

		try {
			request.setAttribute("dataset", generateJSON(repository, "en", null));
		} catch(Exception e) {}

		// Add comments to request
		SQLiteCommentDBInstance dbInstance = SQLiteCommentDBInstance.getInstance();

		// Select all from comments
		ArrayList<HashMap> comments = null;
		try {
			 comments = dbInstance.getComments();
		} catch (NullPointerException e) {
			log.error("Could not access database... " + e.getMessage());
		}

		request.setAttribute("comments", comments);
		// forward to the JSP
		return new ModelAndView("viz-treelayout");
	}

	@RequestMapping("/about")
	public ModelAndView about(HttpServletRequest request) {

		// retrieve resource bundle for error messages
		ResourceBundle b = ResourceBundle.getBundle(
				"fr.sparna.rdf.skosplay.i18n.Bundle",
				SessionData.get(request.getSession()).getUserLocale(),
				new StrictResourceBundleControl()
		);

		return new ModelAndView(b.getString("about.jsp"));
	}

	@RequestMapping("/style/custom.css")
	public void style(
			HttpServletRequest request,
			HttpServletResponse response
	) throws Exception {

		if(SkosPlayConfig.getInstance().getCustomCss() != null) {
			try {
				log.debug("Reading and returning custom CSS from "+SkosPlayConfig.getInstance().getCustomCss());
				String content = ReadWriteTextFile.getContents(SkosPlayConfig.getInstance().getCustomCss());
				response.getOutputStream().write(content.getBytes());
				response.flushBuffer();
			} catch (FileNotFoundException e) {
				// should not happen
				throw e;
			} catch (IOException e) {
				log.error("Exception while reading custom CSS from "+SkosPlayConfig.getInstance().getCustomCss().getAbsolutePath());
				throw e;
			}
		}
	}

	@RequestMapping(value = "/comments", method = RequestMethod.POST)
	public String postComment(
		@RequestParam(value="username", required=true) String username,
		@RequestParam(value="content", required=true) String content
		) {

		SQLiteCommentDBInstance instance = SQLiteCommentDBInstance.getInstance();
		if (instance.addComment(username, content, "concept")) {
			log.info("Insert executed successfully.");
		}

		log.error("There was an error inserting to the database.");

		return "redirect:home";
	}


	@RequestMapping(value = "/upload", method = RequestMethod.GET)
	public ModelAndView uploadForm() {
		// set an empty Model - just so that JSP can access SkosPlayConfig through it
		UploadFormData data = new UploadFormData();
		return new ModelAndView("upload", UploadFormData.KEY, data);
	}

	@RequestMapping(value = "/upload", method = RequestMethod.POST)
	public ModelAndView upload(
			// radio box indicating type of input
			@RequestParam(value="source", required=true) String sourceString,
			// uploaded file if source=file
			@RequestParam(value="file", required=false) MultipartFile file,
			// reference example if source=example
			@RequestParam(value="example", required=false) String example,
			// url of file or SPARQL endpoint if source=url
			@RequestParam(value="url", required=false) String url,
			// flag indicating to apply RDFS inference or not
			@RequestParam(value="rdfsInference", required=false) boolean rdfsInference,
			// flag indicating to transform OWL to SKOS
			@RequestParam(value="owl2skos", required=false) boolean owl2skos,
			// flag indicating to transform SKOS-XL to SKOS
			@RequestParam(value="skosxl2skos", required=false) boolean skosxl2skos,
			HttpServletRequest request
	) throws IOException {

		log.debug("upload(source="+sourceString+",example="+example+",url="+url+",rdfsInference="+rdfsInference+", owl2skos="+owl2skos+")");

		// get the source
		SOURCE_TYPE source = SOURCE_TYPE.valueOf(sourceString.toUpperCase());

		// retrieve session
		final SessionData sessionData = SessionData.get(request.getSession());

		// prepare data structure
		final PrintFormData printFormData = new PrintFormData();
		sessionData.setPrintFormData(printFormData);

		// retrieve resource bundle for error messages
		ResourceBundle b = ResourceBundle.getBundle(
				"fr.sparna.rdf.skosplay.i18n.Bundle",
				sessionData.getUserLocale(),
				new StrictResourceBundleControl()
		);

		RepositoryBuilder localRepositoryBuilder;

		if(rdfsInference) {
			localRepositoryBuilder = new RepositoryBuilder(new LocalMemoryRepositoryFactory(FactoryConfiguration.RDFS_AWARE));
			// load the SKOS model to be able to infer skos:inScheme from skos:isTopConceptOf
			localRepositoryBuilder.addOperation(new LoadFromFileOrDirectory("skos.rdf"));
		} else {
			localRepositoryBuilder = new RepositoryBuilder();
		}

		Repository repository;
		try {
			switch(source) {
			case FILE : {
				// get uploaded file
				if(file.isEmpty()) {
					return doError(request, "Uploaded file is empty");
				}

				log.debug("Uploaded file name is "+file.getOriginalFilename());
				localRepositoryBuilder.addOperation(new LoadFromStream(file.getInputStream(), Rio.getParserFormatForFileName(file.getOriginalFilename(), RDFFormat.RDFXML)));
				repository = localRepositoryBuilder.createNewRepository();

				// apply rules if needed
				try {
					if(owl2skos) {
						// apply inference
						ApplyUpdates au = new ApplyUpdates(SparqlUpdate.fromUpdateList(SKOSRules.getOWL2SKOSRuleset()));
						au.execute(repository);
					}

					if(skosxl2skos) {
						// apply inference
						ApplyUpdates au = new ApplyUpdates(SparqlUpdate.fromUpdateList(SKOSRules.getSKOSXLRuleset()));
						au.execute(repository);
					}
				} catch (RepositoryOperationException e1) {
					return doError(request, e1.getMessage());
				}

				break;
			}
			case EXAMPLE : {
				// get resource param
				String resourceParam = example;
				if(resourceParam == null || resourceParam.equals("")) {
					return doError(request, "Select an example from the list.");
				}
				repository = SkosPlayConfig.getInstance().getApplicationData().getExampleDatas().get(resourceParam);

				// apply rules if needed
				try {
					if(owl2skos) {
						// apply inference
						ApplyUpdates au = new ApplyUpdates(SparqlUpdate.fromUpdateList(SKOSRules.getOWL2SKOSRuleset()));
						au.execute(repository);
					}

					if(skosxl2skos) {
						// apply inference
						ApplyUpdates au = new ApplyUpdates(SparqlUpdate.fromUpdateList(SKOSRules.getSKOSXLRuleset()));
						au.execute(repository);
					}
				} catch (RepositoryOperationException e1) {
					return doError(request, e1.getMessage());
				}

				// set the loaded data name
				try {
					printFormData.setLoadedDataName(sessionData.getPreLoadedDataLabels().getString(example));
				} catch (Exception e) {
					// missing label : set the key
					printFormData.setLoadedDataName(example);
				}

				break;
			}
			case URL : {
				// we are loading an RDF file from the web, use the localRepositoryBuilder and apply inference if required
				if(!StringRepositoryFactory.isEndpointURL(url)) {

					localRepositoryBuilder.addOperation(new LoadFromUrl(new URL(url)));
					repository = localRepositoryBuilder.createNewRepository();

					// apply OWL2SKOS rules if needed
					try {
						if(owl2skos && !StringRepositoryFactory.isEndpointURL(url)) {
							// apply inference
							ApplyUpdates au = new ApplyUpdates(SparqlUpdate.fromUpdateList(SKOSRules.getOWL2SKOSRuleset()));
							au.execute(repository);
						}
					} catch (RepositoryOperationException e1) {
						return doError(request, e1.getMessage());
					}
				} else {
					// this is a endpoint
					repository = RepositoryBuilder.fromString(url, rdfsInference);
				}

				break;
			}
			default : {
				repository = null;
				break;
			}
			}
		} catch (RepositoryFactoryException e) {
			return doError(request, e);
		}

		int count = -1;
		try {
			// check that data does not contain more than X concepts
			count = Perform.on(repository).count(new SparqlQuery(new SparqlQueryBuilder(this, "CountConcepts.rq")));

			// check that data contains at least one SKOS Concept
			if(count <= 0) {
				return doError(request, b.getString("upload.error.noConceptsFound"));
			}

			int limitConfiguration = SkosPlayConfig.getInstance().getConceptsLimit();
			if(
					source != SOURCE_TYPE.EXAMPLE
					&&
					limitConfiguration > 0
					&&
				    count > limitConfiguration
			) {
				return doError(
						request,
						MessageFormat.format(
								b.getString("upload.error.dataTooLarge"),
								limitConfiguration
						)
				);
			}

		} catch (SparqlPerformException e) {
			e.printStackTrace();
			return doError(request, e);
		}

		// set loaded data licence, if any
		try {
			Value license = Perform.on(repository).read(new SparqlQuery(new SparqlQueryBuilder(this, "ReadLicense.rq")));
			if(license != null && license instanceof Literal) {
				printFormData.setLoadedDataLicense(((Literal)license).stringValue());
			}
		} catch (SparqlPerformException e) {
			e.printStackTrace();
			return doError(request, e);
		}

		// store repository in the session
		sessionData.setRepository(repository);

		// store sourceConceptLabel reader in the session
		// default to no language
		final LabelReader labelReader = new LabelReader(repository, "", sessionData.getUserLocale().getLanguage());
		// add dcterms title and dc title
		labelReader.getProperties().add(URI.create(DCTERMS.TITLE.toString()));
		labelReader.getProperties().add(URI.create(DC.TITLE.toString()));
		sessionData.setLabelReader(labelReader);

		// store success message with number of concepts
		printFormData.setSuccessMessage(MessageFormat.format(b.getString("print.message.numberOfConcepts"), count));

		if(DisplayType.needHierarchyCheck() || VizType.needHierarchyCheck()) {
			try {
				// ask if some hierarchy exists
				if(!Perform.on(repository).ask(new SparqlQuery(new SparqlQueryBuilder(this, "AskBroadersOrNarrowers.rq")))) {
					printFormData.setEnableHierarchical(false);
					printFormData.getWarningMessages().add(b.getString("upload.warning.noHierarchyFound"));
				}
			} catch (SparqlPerformException e) {
				printFormData.setEnableHierarchical(false);
				printFormData.getWarningMessages().add(b.getString("upload.warning.noHierarchyFound"));
			}
		}

		if(DisplayType.needTranslationCheck()) {
			try {
				// ask if some translations exists
				if(!Perform.on(repository).ask(new SparqlQuery(new SparqlQueryBuilder(this, "AskTranslatedConcepts.rq")))) {
					printFormData.setEnableTranslations(false);
					printFormData.getWarningMessages().add(b.getString("upload.warning.noTranslationsFound"));
				}
			} catch (SparqlPerformException e) {
				printFormData.setEnableTranslations(false);
				printFormData.getWarningMessages().add(b.getString("upload.warning.noTranslationsFound"));
			}
		}

		if(DisplayType.needAlignmentCheck()) {
			try {
				// ask if some alignments exists
				if(!Perform.on(repository).ask(new SparqlQuery(new SparqlQueryBuilder(this, "AskMappings.rq")))) {
					printFormData.setEnableMappings(false);
					printFormData.getWarningMessages().add(b.getString("upload.warning.noMappingsFound"));
				}
			} catch (SparqlPerformException e) {
				printFormData.setEnableMappings(false);
				printFormData.getWarningMessages().add(b.getString("upload.warning.noMappingsFound"));
			}
		}

		try {
			// retrieve number of concepts per concept schemes
			Perform.on(repository).select(new SelectSparqlHelper(
					new SparqlQueryBuilder(this, "ConceptCountByConceptSchemes.rq"),
					new TupleQueryResultHandlerBase() {

						@Override
						public void handleSolution(BindingSet bindingSet)
						throws TupleQueryResultHandlerException {
							if(bindingSet.getValue("scheme") != null) {
								try {
									printFormData.getConceptCountByConceptSchemes().put(
											new LabeledResource(
													java.net.URI.create(bindingSet.getValue("scheme").stringValue()),
													LabelReader.display(labelReader.getValues((org.openrdf.model.URI)bindingSet.getValue("scheme")))
											),
											(bindingSet.getValue("conceptCount") != null)?
													((Literal)bindingSet.getValue("conceptCount")).intValue()
													:0
									);
								} catch (SparqlPerformException e) {
									throw new TupleQueryResultHandlerException(e);
								}
							}
						}
					}
			));

			// retrieve list of declared languages in the data
			Perform.on(repository).select(new SelectSparqlHelper(
					new SparqlQueryBuilder(this, "ListOfSkosLanguages.rq"),
					new TupleQueryResultHandlerBase() {

						@Override
						public void handleSolution(BindingSet bindingSet)
						throws TupleQueryResultHandlerException {
							String rdfLanguage = bindingSet.getValue("language").stringValue();
							Language l = fr.sparna.rdf.sesame.toolkit.languages.Languages.getInstance().withIso639P1(rdfLanguage);
							String languageName = (l != null)?l.displayIn(sessionData.getUserLocale().getLanguage()):rdfLanguage;
							printFormData.getLanguages().put(
									bindingSet.getValue("language").stringValue(),
									languageName
							);
						}

					}
			));
		} catch (SparqlPerformException e) {
			return doError(request, e);
		}


		return new ModelAndView("print");
	}

	protected ModelAndView doError(
			HttpServletRequest request,
			Exception e
	) {
		// print stack trace
		e.printStackTrace();
		// build on-screen error message
		StringBuffer message = new StringBuffer(e.getMessage());
		Throwable current = e.getCause();
		while(current != null) {
			message.append(". Cause : "+current.getMessage());
			current = current.getCause();
		}
		return doError(request, message.toString());
	}

	protected ModelAndView doError(
			HttpServletRequest request,
			String message
	) {
		UploadFormData data = new UploadFormData();
		data.setErrorMessage(message);
		request.setAttribute(UploadFormData.KEY, data);
		return new ModelAndView("upload");
	}


	@RequestMapping(
			value = "/visualize",
			method = RequestMethod.POST
	)
	public ModelAndView visualize(
			// output type, PDF or HTML
			@RequestParam(value="display", required=true) String displayParam,
			@RequestParam(value="language", defaultValue="no-language") String language,
			@RequestParam(value="scheme", defaultValue="no-scheme") String schemeParam,
			HttpServletRequest request,
			HttpServletResponse response
	) throws Exception {

		// get viz type param
		VizType displayType = (displayParam != null)?VizType.valueOf(displayParam.toUpperCase()):null;

		// get scheme param
		URI scheme = (schemeParam.equals("no-scheme"))?null:URI.create(schemeParam);

		// retrieve data from session
		Repository r = SessionData.get(request.getSession()).getRepository();

		// make a log to trace usage
		String aRandomConcept = Perform.on(r).read(new SparqlQuery(new SparqlQueryBuilder(this, "ReadRandomConcept.rq"))).stringValue();
		log.info("PRINT,"+SimpleDateFormat.getDateTimeInstance().format(new Date())+","+scheme+","+aRandomConcept+","+language+","+displayType+","+"HTML");

		switch(displayType) {
		case PARTITION : {
			request.setAttribute("dataset", generateJSON(r, language, scheme));
			// forward to the JSP
			return new ModelAndView("viz-partition");
		}
		case TREELAYOUT : {
			request.setAttribute("dataset", generateJSON(r, language, scheme));
			// forward to the JSP
			return new ModelAndView("viz-treelayout");
		}
		case SUNBURST : {
			request.setAttribute("dataset", generateJSON(r, language, scheme));
			// forward to the JSP
			return new ModelAndView("viz-sunburst");
		}
		case AUTOCOMPLETE : {
			AutocompleteItemsReader autocompleteReader = new AutocompleteItemsReader();
			Items items = autocompleteReader.readItems(r, language, scheme);
			JSONWriter writer = new JSONWriter();
			request.setAttribute("items", writer.write(items));
			// forward to the JSP
			return new ModelAndView("viz-autocomplete");
		}
		default : {
			throw new InvalidParameterException("Unknown display type "+displayType);
		}
		}

	}


	@RequestMapping(value = "/print", method = RequestMethod.POST)
	public void print(
			// output type, PDF or HTML
			@RequestParam(value="output", required=true) String outputParam,
			@RequestParam(value="display", required=true) String displayParam,
			@RequestParam(value="language", defaultValue="no-language") String language,
			@RequestParam(value="scheme", defaultValue="no-scheme") String schemeParam,
			@RequestParam(value="targetLanguage", defaultValue="no-language") String targetLanguageParam,
			HttpServletRequest request,
			HttpServletResponse response
	) throws Exception {

		// get output type param
		OutputType outputType = (outputParam != null)?OutputType.valueOf(outputParam.toUpperCase()):null;

		// get display type param
		DisplayType displayType = (displayParam != null)?DisplayType.valueOf(displayParam.toUpperCase()):null;

		// get scheme param
		URI scheme = (schemeParam.equals("no-scheme"))?null:URI.create(schemeParam);

		// get target language param - only for translations
		String targetLanguage = (targetLanguageParam != null)?(targetLanguageParam.equals("no-language")?null:targetLanguageParam):null;

		// retrieve data from session
		Repository r = SessionData.get(request.getSession()).getRepository();

		// make a log to trace usage
		String aRandomConcept = Perform.on(r).read(new SparqlQuery(new SparqlQueryBuilder(this, "ReadRandomConcept.rq"))).stringValue();
		log.info("PRINT,"+SimpleDateFormat.getDateTimeInstance().format(new Date())+","+scheme+","+aRandomConcept+","+language+","+displayType+","+outputType);

		// build display result
		KosDocument document = new KosDocument();


		HeaderAndFooterReader headerReader = new HeaderAndFooterReader(r);
		headerReader.setApplicationString("Generated by SKOS Play!, sparna.fr");
		// on désactive complètement le header pour les PDF
		if(outputType != OutputType.PDF) {
			// build and set header
			document.setHeader(headerReader.readHeader(language, scheme));
		}
		// all the time, set footer
		document.setFooter(headerReader.readFooter(language, scheme));

		// pass on Repository to skos-printer level
		BodyReader bodyReader;
		switch(displayType) {
		case ALPHABETICAL : {
			ConceptBlockReader cbr = new ConceptBlockReader(r);
			bodyReader = new BodyReader(new AlphaIndexDisplayGenerator(r, cbr));
			break;
		}
		case ALPHABETICAL_EXPANDED : {
			ConceptBlockReader cbr = new ConceptBlockReader(r);
			cbr.setSkosPropertiesToRead(AlphaIndexDisplayGenerator.EXPANDED_SKOS_PROPERTIES_WITH_TOP_TERMS);
			bodyReader = new BodyReader(new AlphaIndexDisplayGenerator(r, cbr));
			break;
		}
		case HIERARCHICAL : {
			bodyReader = new BodyReader(new HierarchicalDisplayGenerator(r, new ConceptBlockReader(r)));
			break;
		}
//			case HIERARCHICAL_EXPANDED : {
//				displayGenerator = new HierarchicalDisplayGenerator(r, new ConceptBlockReader(r, HierarchicalDisplayGenerator.EXPANDED_SKOS_PROPERTIES));
//				break;
//			}
		case CONCEPT_LISTING : {
			ConceptBlockReader cbr = new ConceptBlockReader(r);
			cbr.setSkosPropertiesToRead(ConceptListDisplayGenerator.EXPANDED_SKOS_PROPERTIES_WITH_TOP_TERMS);
			List<String> additionalLanguages = new ArrayList<String>();
			for (String aLang : SessionData.get(request.getSession()).getPrintFormData().getLanguages().keySet()) {
				if(!aLang.equals(language)) {
					additionalLanguages.add(aLang);
				}
			}
			cbr.setAdditionalLabelLanguagesToInclude(additionalLanguages);

			bodyReader = new BodyReader(new ConceptListDisplayGenerator(r, cbr));
			break;
		}
		case TRANSLATION_TABLE : {
			bodyReader = new BodyReader(new TranslationTableDisplayGenerator(r, new ConceptBlockReader(r), targetLanguage));
			break;
		}
		case PERMUTED_INDEX : {
			bodyReader = new BodyReader(new IndexGenerator(r, IndexType.KWAC));
			break;
		}
		case KWIC_INDEX : {
			bodyReader = new BodyReader(new IndexGenerator(r, IndexType.KWIC));
			break;
		}
		case COMPLETE_MONOLINGUAL : {

			// prepare a list of generators
			List<AbstractKosDisplayGenerator> generators = new ArrayList<AbstractKosDisplayGenerator>();

			// alphabetical display
			ConceptBlockReader alphaCbReader = new ConceptBlockReader(r);
			alphaCbReader.setStyleAttributes(true);
			alphaCbReader.setSkosPropertiesToRead(AlphaIndexDisplayGenerator.EXPANDED_SKOS_PROPERTIES_WITH_TOP_TERMS);
			alphaCbReader.setLinkDestinationIdPrefix("hier");
			AlphaIndexDisplayGenerator alphaGen = new AlphaIndexDisplayGenerator(
					r,
					alphaCbReader,
					"alpha"
			);
			generators.add(alphaGen);

			// hierarchical display
			ConceptBlockReader hierCbReader = new ConceptBlockReader(r);
			hierCbReader.setLinkDestinationIdPrefix("alpha");
			HierarchicalDisplayGenerator hierarchyGen = new HierarchicalDisplayGenerator(
					r,
					hierCbReader,
					"hier"
			);
			generators.add(hierarchyGen);

			bodyReader = new BodyReader(generators);

			break;
		}
		case COMPLETE_MULTILINGUAL : {

			// prepare a list of generators
			List<AbstractKosDisplayGenerator> generators = new ArrayList<AbstractKosDisplayGenerator>();

			// read all potential languages and exclude the main one
			final List<String> additionalLanguages = new ArrayList<String>();
			for (String aLang : SessionData.get(request.getSession()).getPrintFormData().getLanguages().keySet()) {
				if(!aLang.equals(language)) {
					additionalLanguages.add(aLang);
				}
			}

			// alphabetical display
			ConceptBlockReader alphaCbReader = new ConceptBlockReader(r);
			alphaCbReader.setStyleAttributes(true);
			alphaCbReader.setSkosPropertiesToRead(AlphaIndexDisplayGenerator.EXPANDED_SKOS_PROPERTIES_WITH_TOP_TERMS);
			alphaCbReader.setAdditionalLabelLanguagesToInclude(additionalLanguages);
			alphaCbReader.setLinkDestinationIdPrefix("hier");
			AlphaIndexDisplayGenerator alphaGen = new AlphaIndexDisplayGenerator(
					r,
					alphaCbReader,
					"alpha"
			);
			generators.add(alphaGen);

			// hierarchical display
			ConceptBlockReader hierCbReader = new ConceptBlockReader(r);
			hierCbReader.setLinkDestinationIdPrefix("alpha");
			HierarchicalDisplayGenerator hierarchyGen = new HierarchicalDisplayGenerator(
					r,
					hierCbReader,
					"hier"
			);
			generators.add(hierarchyGen);

			// add translation tables for each additional languages
			for (int i=0;i<additionalLanguages.size(); i++) {
				String anAdditionalLang = additionalLanguages.get(i);
				ConceptBlockReader aCbReader = new ConceptBlockReader(r);
				aCbReader.setLinkDestinationIdPrefix("alpha");
				TranslationTableReverseDisplayGenerator ttGen = new TranslationTableReverseDisplayGenerator(
						r,
						aCbReader,
						anAdditionalLang,
						"trans"+i);
				generators.add(ttGen);
			}

			bodyReader = new BodyReader(generators);

			break;
		}
		case ALIGNMENT_ALPHA : {
			AlignmentDataHarvesterIfc harvester = new AlignmentDataHarvesterCachedLoader(null, RDFFormat.RDFXML);
			AlignmentDisplayGenerator adg = new AlignmentDisplayGenerator(r, new ConceptBlockReader(r), harvester);
			// this is the difference with other alignment display
			adg.setSeparateByTargetScheme(false);
			bodyReader = new BodyReader(adg);
			break;
		}
		case ALIGNMENT_BY_SCHEME : {
			AlignmentDataHarvesterIfc harvester = new AlignmentDataHarvesterCachedLoader(null, RDFFormat.RDFXML);
			AlignmentDisplayGenerator adg = new AlignmentDisplayGenerator(r, new ConceptBlockReader(r), harvester);
			// this is the difference with other alignment display
			adg.setSeparateByTargetScheme(true);
			bodyReader = new BodyReader(adg);
			break;
		}
		default :
			throw new InvalidParameterException("Unknown display type "+displayType);
		}

		// read the body
		document.setBody(bodyReader.readBody(language, scheme));

		DisplayPrinter printer = new DisplayPrinter();
		// TODO : use Spring for configuration for easier debugging config
		// for the moment we desactivate debugging completely
		printer.setDebug(false);

		switch(outputType) {
		case HTML : {
			printer.printToHtml(document, response.getOutputStream(), SessionData.get(request.getSession()).getUserLocale().getLanguage());
			break;
		}
		case PDF : {
			response.setContentType("application/pdf");
			// if alphabetical or concept listing display, set 2-columns layout
			if(
					displayType == DisplayType.ALPHABETICAL
					||
					displayType == DisplayType.CONCEPT_LISTING
					||
					displayType == DisplayType.ALPHABETICAL_EXPANDED
			) {
				printer.getTransformerParams().put("column-count", 2);
			}
			printer.printToPdf(document, response.getOutputStream(), SessionData.get(request.getSession()).getUserLocale().getLanguage());
			break;
		}
		}

		response.flushBuffer();
	}

	protected String generateJSON (
			Repository r,
			String language,
			URI scheme
	) throws Exception {
		SKOSTreeBuilder builder = new SKOSTreeBuilder(r, language);

		GenericTree<SKOSTreeNode> tree = buildTree(builder, (scheme != null)?URI.create(scheme.toString()):null);

		// writes json output
		LabelReader labelReader = new LabelReader(r, language);
		JsonSKOSTreePrinter printer = new JsonSKOSTreePrinter(labelReader);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		printer.print(tree, baos);
		return baos.toString("UTF-8").replaceAll("'", "\\\\'");
	}

	public GenericTree<SKOSTreeNode> buildTree(SKOSTreeBuilder builder, URI root)
	throws SparqlPerformException {
		GenericTree<SKOSTreeNode> tree = new GenericTree<SKOSTreeNode>();

		List<GenericTree<SKOSTreeNode>> trees;

		if(root != null) {
			// generates tree
			log.debug("Building tree with root "+root);
			trees = builder.buildTrees(root);
		} else {
			// fetch all trees
			log.debug("Building tree with no particular root ");
			trees = builder.buildTrees();
		}

		// if only one, set it as root
		if(trees.size() == 1) {
			log.debug("Single tree found in the result");
			tree = trees.get(0);
		} else if (trees.size() ==0) {
			log.warn("Warning, no trees found");
		} else {
			log.debug("Multiple trees found ("+trees.size()+"), will create a fake root to group them all");
			// otherwise, create a fake root
			GenericTreeNode<SKOSTreeNode> fakeRoot = new GenericTreeNode<SKOSTreeNode>();
			fakeRoot.setData(new SKOSTreeNode(URI.create("skosplay:allData"), "", NodeType.UNKNOWN));

			// add all the trees under it
			for (GenericTree<SKOSTreeNode> genericTree : trees) {
				log.debug("Addind tree under fake root : "+genericTree.getRoot().getData().getUri());
				fakeRoot.addChild(genericTree.getRoot());
			}

			// set the root of the tree
			tree.setRoot(fakeRoot);
		}

		return tree;
	}

}
