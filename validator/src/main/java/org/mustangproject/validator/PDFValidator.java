package org.mustangproject.validator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.mustangproject.ZUGFeRD.ZUGFeRDImporter;
import org.riversun.bigdoc.bin.BigFileSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.verapdf.core.VeraPDFException;
import org.verapdf.features.FeatureExtractorConfig;
import org.verapdf.features.FeatureFactory;
import org.verapdf.metadata.fixer.FixerFactory;
import org.verapdf.metadata.fixer.MetadataFixerConfig;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.validation.validators.ValidatorConfig;
import org.verapdf.pdfa.validation.validators.ValidatorFactory;
import org.verapdf.processor.BatchProcessor;
import org.verapdf.processor.FormatOption;
import org.verapdf.processor.ProcessorConfig;
import org.verapdf.processor.ProcessorFactory;
import org.verapdf.processor.TaskType;
import org.verapdf.processor.plugins.PluginsCollectionConfig;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class PDFValidator extends Validator {

	public PDFValidator(ValidationContext ctx) {
		super(ctx);
		// TODO Auto-generated constructor stub
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(PDFValidator.class.getCanonicalName()); // log output
																											// is

	private String pdfFilename;

	private String pdfReport;

	private String Signature;

	private String zfXML = null;

	protected static boolean stringArrayContains(String[] arr, String targetValue) {
		return Arrays.asList(arr).contains(targetValue);
	}

	@Override
  public void validate() throws IrrecoverableValidationError {

		zfXML = null;
		final File file = new File(pdfFilename);
		// file existence must have been checked before
		final BigFileSearcher searcher = new BigFileSearcher();

		final byte[] pdfSignature = { '%', 'P', 'D', 'F' };
		if (searcher.indexOf(file, pdfSignature) != 0) {
			context.addResultItem(
					new ValidationResultItem(ESeverity.fatal, "Not a PDF file "+pdfFilename).setSection(20).setPart(EPart.pdf));

		}

		final long startPDFTime = Calendar.getInstance().getTimeInMillis();

		// Step 1 Validate PDF

		VeraGreenfieldFoundryProvider.initialise();
		// Default validator config
		final ValidatorConfig validatorConfig = ValidatorFactory.defaultConfig();
		// Default features config
		final FeatureExtractorConfig featureConfig = FeatureFactory.defaultConfig();
		// Default plugins config
		final PluginsCollectionConfig pluginsConfig = PluginsCollectionConfig.defaultConfig();
		// Default fixer config
		final MetadataFixerConfig fixerConfig = FixerFactory.defaultConfig();
		// Tasks configuring
		final EnumSet tasks = EnumSet.noneOf(TaskType.class);
		tasks.add(TaskType.VALIDATE);
		// tasks.add(TaskType.EXTRACT_FEATURES);
		// tasks.add(TaskType.FIX_METADATA);
		// Creating processor config
		final ProcessorConfig processorConfig = ProcessorFactory.fromValues(validatorConfig, featureConfig, pluginsConfig,
				fixerConfig, tasks);
		// Creating processor and output stream.
		final ByteArrayOutputStream reportStream = new ByteArrayOutputStream();
		try (BatchProcessor processor = ProcessorFactory.fileBatchProcessor(processorConfig)) {
			// Generating list of files for processing
			final List<File> files = new ArrayList<>();
			files.add(new File(pdfFilename));
			// starting the processor
                        processor.process(files, ProcessorFactory.getHandler(FormatOption.MRR, true, reportStream,
                                                        processorConfig.getValidatorConfig().isRecordPasses()));                        
			pdfReport = reportStream.toString("utf-8").replaceAll("<\\?xml version=\"1\\.0\" encoding=\"utf-8\"\\?>",
					"");
		} catch (final VeraPDFException e) {
			final ValidationResultItem vri = new ValidationResultItem(ESeverity.exception, e.getMessage()).setSection(6)
					.setPart(EPart.pdf);
			final StringWriter sw = new StringWriter();
			final PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			vri.setStacktrace(sw.toString());
			context.addResultItem(vri);
		} catch (final IOException excep) {
			context.addResultItem(new ValidationResultItem(ESeverity.exception, excep.getMessage()).setSection(7)
					.setPart(EPart.pdf).setStacktrace(excep.getStackTrace().toString()));
		}

		// step 2 validate XMP
		final ZUGFeRDImporter zi = new ZUGFeRDImporter(pdfFilename);
		final String xmp = zi.getXMP();

		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		final Document docXMP;

		if (xmp.length() == 0) {
			context.addResultItem(new ValidationResultItem(ESeverity.error, "Invalid XMP Metadata not found")
					.setSection(17).setPart(EPart.pdf));
		}
		/*
		 * checking for sth like <zf:ConformanceLevel>EXTENDED</zf:ConformanceLevel>
		 * <zf:DocumentType>INVOICE</zf:DocumentType>
		 * <zf:DocumentFileName>ZUGFeRD-invoice.xml</zf:DocumentFileName>
		 * <zf:Version>1.0</zf:Version>
		 */
		try {
			final DocumentBuilder builder = factory.newDocumentBuilder();
			final InputSource is = new InputSource(new StringReader(xmp));
			docXMP = builder.parse(is);

			final XPathFactory xpathFactory = XPathFactory.newInstance();

			// Create XPath object XPath xpath = xpathFactory.newXPath(); XPathExpression

			final XPath xpath = xpathFactory.newXPath();
			// xpath.compile("//*[local-name()=\"GuidelineSpecifiedDocumentContextParameter\"]/[local-name()=\"ID\"]");
			// evaluate expression result on XML document ndList = (NodeList)

			// get the first element
			XPathExpression xpr = xpath.compile(
					"//*[local-name()=\"ConformanceLevel\"]|//*[local-name()=\"Description\"]/@ConformanceLevel");
			NodeList nodes = (NodeList) xpr.evaluate(docXMP, XPathConstants.NODESET);

			if (nodes.getLength() == 0) {
				context.addResultItem(
						new ValidationResultItem(ESeverity.error, "XMP Metadata: ConformanceLevel not found")
								.setSection(11).setPart(EPart.pdf));
			}
	
			boolean conformanceLevelValid=false;
			for (int i = 0; i < nodes.getLength(); i++) {

				final String[] valueArray = { "BASIC WL", "BASIC", "MINIMUM", "EN 16931", "COMFORT", "CIUS", "EXTENDED", "XRECHNUNG" };
				if (stringArrayContains(valueArray, nodes.item(i).getTextContent())) {
					conformanceLevelValid=true;
				}
			}
			if (!conformanceLevelValid) {
				context.addResultItem(new ValidationResultItem(ESeverity.error,
						"XMP Metadata: ConformanceLevel contains invalid value").setSection(12).setPart(EPart.pdf));

			}
			xpr = xpath.compile("//*[local-name()=\"DocumentType\"]|//*[local-name()=\"Description\"]/@DocumentType");
			nodes = (NodeList) xpr.evaluate(docXMP, XPathConstants.NODESET);

			if (nodes.getLength() == 0) {
				context.addResultItem(new ValidationResultItem(ESeverity.error, "XMP Metadata: DocumentType not found")
						.setSection(13).setPart(EPart.pdf));
			}

			boolean documentTypeValid=false;
			for (int i = 0; i < nodes.getLength(); i++) {
				if (nodes.item(i).getTextContent().equals("INVOICE")||nodes.item(i).getTextContent().equals("ORDER")||nodes.item(i).getTextContent().equals("ORDER_RESPONSE")||nodes.item(i).getTextContent().equals("ORDER_CHANGE")) {
					documentTypeValid=true;
				}
			}
			if (!documentTypeValid) {
				context.addResultItem(
						new ValidationResultItem(ESeverity.error, "XMP Metadata: DocumentType invalid")
								.setSection(14).setPart(EPart.pdf));

			}
			xpr = xpath.compile(
					"//*[local-name()=\"DocumentFileName\"]|//*[local-name()=\"Description\"]/@DocumentFileName");
			nodes = (NodeList) xpr.evaluate(docXMP, XPathConstants.NODESET);

			if (nodes.getLength() == 0) {
				context.addResultItem(
						new ValidationResultItem(ESeverity.error, "XMP Metadata: DocumentFileName not found")
								.setSection(21).setPart(EPart.pdf));
			}
			boolean documentFilenameValid=false;
			for (int i = 0; i < nodes.getLength(); i++) {
				final String[] valueArray = { "factur-x.xml", "ZUGFeRD-invoice.xml", "zugferd-invoice.xml", "xrechnung.xml" , "order-x.xml" };
				if (stringArrayContains(valueArray, nodes.item(i).getTextContent())) {
					documentFilenameValid=true;
				}

				// e.g. ZUGFeRD-invoice.xml
			}
			if (!documentFilenameValid) {

				context.addResultItem(new ValidationResultItem(ESeverity.error,
						"XMP Metadata: DocumentFileName contains invalid value").setSection(19).setPart(EPart.pdf));
			}
			xpr = xpath.compile("//*[local-name()=\"Version\"]|//*[local-name()=\"Description\"]/@Version");
			nodes = (NodeList) xpr.evaluate(docXMP, XPathConstants.NODESET);

			// get all child nodes
			// NodeList nodes = element.getChildNodes();
			// expr.evaluate(docXMP, XPathConstants.NODESET);
			// print the text content of each child
			if (nodes.getLength() == 0) {
				context.addResultItem(new ValidationResultItem(ESeverity.error, "XMP Metadata: Version not found")
						.setSection(15).setPart(EPart.pdf));
			}

			boolean versionValid=false;
			for (int i = 0; i < nodes.getLength(); i++) {
				final String[] valueArray = { "1.0", "2p0", "1.2", "2.0" , "2.1" }; //1.2, 2.0 and 2.1 are for xrechnung 1.2, 2p0 can be ZF 2.0, 2.1, 2.1.1
				if (stringArrayContains(valueArray, nodes.item(i).getTextContent())) {
					versionValid=true;
				} // e.g. 1.0
			}
			if (!versionValid) {
				context.addResultItem(
						new ValidationResultItem(ESeverity.error, "XMP Metadata: Version contains invalid value")
								.setSection(16).setPart(EPart.pdf));

			}

		} catch (final SAXException e) {
			LOGGER.error(e.getMessage(), e);
		} catch (final IOException e) {
			LOGGER.error(e.getMessage(), e);
		} catch (final ParserConfigurationException e) {
			LOGGER.error(e.getMessage(), e);
		} catch (final XPathExpressionException e) {
			LOGGER.error(e.getMessage(), e);
		}
		zfXML = zi.getUTF8();

		// step 3 find signatures
		try {
			final byte[] symtraxSignature = "Symtrax".getBytes("UTF-8");
			final byte[] mustangSignature = "via mustangproject".getBytes("UTF-8");
			final byte[] facturxpythonSignature = "by Alexis de Lattre".getBytes("UTF-8");
			final byte[] intarsysSignature = "intarsys ".getBytes("UTF-8");
			final byte[] konikSignature = "Konik".getBytes("UTF-8");
			final byte[] pdfMachineSignature = "pdfMachine from Broadgun Software".getBytes("UTF-8");
			final byte[] ghostscriptSignature = "%%Invocation:".getBytes("UTF-8");

			if (searcher.indexOf(file, symtraxSignature) != -1) {
				Signature = "Symtrax";
			} else if (searcher.indexOf(file, mustangSignature) != -1) {
				Signature = "Mustang";
			} else if (searcher.indexOf(file, facturxpythonSignature) != -1) {
				Signature = "Factur/X Python";
			} else if (searcher.indexOf(file, intarsysSignature) != -1) {
				Signature = "Intarsys";
			} else if (searcher.indexOf(file, konikSignature) != -1) {
				Signature = "Konik";
			} else if (searcher.indexOf(file, pdfMachineSignature) != -1) {
				Signature = "pdfMachine";
			} else if (searcher.indexOf(file, ghostscriptSignature) != -1) {
				Signature = "Ghostscript";
			}

			context.setSignature(Signature);

		} catch (final UnsupportedEncodingException e) {
			LOGGER.error(e.getMessage(), e);
		}

		// step 4:validate additional data
		final HashMap<String, byte[]> additionalData=zi.getAdditionalData();
		for (final String filename : additionalData.keySet()) {
			// validating xml in byte[]	additionalData.get(filename)
			LOGGER.info("validating additionalData " + filename);
			validateSchema(additionalData.get(filename), "ad/basic/additional_data_base_schema.xsd", 2, EPart.pdf);
		}
		
		
		//end

		final long endTime = Calendar.getInstance().getTimeInMillis();
		if (!pdfReport.contains("validationReports compliant=\"1\"")) {
			context.setInvalid();
		}
		if (!pdfReport.contains("PDF/A-3")) {
			context.addResultItem(
					new ValidationResultItem(ESeverity.error, "Not a PDF/A-3").setSection(23).setPart(EPart.pdf));

		}
		context.addCustomXML(pdfReport + "<info><signature>"
				+ ((context.getSignature() != null) ? context.getSignature() : "unknown")
				+ "</signature><duration unit=\"ms\">" + (endTime - startPDFTime) + "</duration></info>");

	}

	
	@Override
	public void setFilename(String filename) throws IrrecoverableValidationError {
		this.pdfFilename = filename;

	}

	public String getRawXML() {
		return zfXML;

	}

	public String getSignature() {
		return Signature;
	}

}
