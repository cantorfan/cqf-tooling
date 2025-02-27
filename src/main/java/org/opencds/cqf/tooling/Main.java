package org.opencds.cqf.tooling;

/*

    The purpose of this project is to provide tooling for several CDS use cases including Implementation Guide and Measure authoring. See below for
    a more comprehensive list of use cases.
    This project is currently configured for ONLY STU3 FHIR.

    Legend:
        - () = optional param
        - [] = required param
        - {} = 0..* params
        - | = OR (typically used for shorthand args)

    Note:
        - The default output directory is src/main/java/resources/org/opencds/cqf/tooling/{package}/output
            - If an output directory path is specified in the params, it MUST NOT have any spaces
        - The default encoding for org.opencds.cqf.qdm.output is JSON
            - XML is also supported

    This project provides tooling for the following use cases:
        - QDM to QiCore mapping generation
            - command: mvn exec:java -Dexec.args="[-QdmToQiCore] (output directory path)"
            - Example: mvn exec:java -Dexec.args="-QdmToQiCore /Users/christopherschuler/Documents/workspace/QdmToQiCoreFiles"
            - This tooling generates HTML pages from http://wiki.hl7.org/index.php?title=Harmonization_of_Health_Quality_Information_models
              for the QiCore implementation guide

        - QiCore QUICK page generation
            - command: mvn exec:java -Dexec.args="[-QiCoreQUICK] [path to QiCore output directory] (output directory path)"
            - /Users/christopherschuler/Documents/workspace/harmoniq/repos/qi-core/output

        - VSAC Excel spreadsheet to FHIR ValueSet resource conversion
            - command: mvn exec:java -Dexec.args="[-VsacXlsxToValueSet] [-pathtospreadsheet | -pts] (-outputpath | -op) (-encoding | -e) (-metasheetnum | -msn) (-metanamerow | -mnr) (-metaoidrow | -mor) (-metastewardrow | -msr) (-codesheetnum | -csn) (-codelistrow | -clr) (-codecol | -cc) (-descriptioncol | -dc) (-systemnamecol | -snc) (-versioncol | -vc) (-systemoidcol | -soc)"
            - Example: mvn exec:java -Dexec.args="-VsacXlsxToValueSet -pts=/Users/christopherschuler/Documents/workspace/exampleValueSet.xlsx"
            - This tooling converts an exported VSAC Excel spreadsheet (.xlsx extension) to a FHIR ValueSet resource and prints to output directory
            - This operation is configurable and can be used with spreadsheets that do not conform to VSAC export format (although we suggest using the -XlsxToValueSet operation as it is much more flexible and configurable).

        - Generic Excel spreadsheet to FHIR ValueSet resource conversion
            - command: mvn exec:java -Dexec.args="[-XlsxToValueSet] [-pathtospreadsheet | -pts] (-outputpath | -op) (-encoding | -e)"
            - Example: TODO
            - This tooling converts an Excel spreadsheet (.xlsx extension) to a FHIR ValueSet resource
            - This is highly configurable
                - TODO

        - CQL to FHIR Library conversion
            - command: mvn exec:java -Dexec.args="[-CqlToSTU3Library|-CqlToR4Library] [-pathtolibrarydirectory | -ptld] (-encoding | -e) (-outputpath | -op)"
            - Example: java -Dexec.args="-CqlToR4Library -ptld=/Users/christopherschuler/Src/cqframework/opioid-cds-r4/pages/cql/ -op=/Users/christopherschuler/Src/cqframework/opioid-cds-r4/resources"
            - This tooling converts CQL libraries to FHIR Library resources
            - The following elements will be populated in the FHIR Library resource:
                - id (auto-generated)
                - version (if declared in the CQL)
                - name (if declared in the CQL)
                - status (draft by default)
                - type (logic-library by default)
                - dataRequirement
                - content.contentType (both application/elm+xml and text/cql by default)
                - content.data (base64 encoded String)

        - Update CQL for an existing Library resource
            - command: mvn exec:java -Dexec.args="[-UpdateCql] [-pathtolibrary | -ptl] [-pathtolibraryresource | -ptlr] (-encoding | -e) (-outputpath | -op)"
            - Example: -UpdateCql -ptl="C:\Users\Bryn\Documents\Src\SS\Pilots\Opioid\opioid-cds\pages\cql\OpioidCDS_STU3_Common.cql" -ptlr="C:\Users\Bryn\Documents\Src\SS\Pilots\Opioid\opioid-cds\resources\library-opioidcds-stu3-common-0-1-0.xml"

        - CQL to FHIR Measure conversion
            - command: mvn exec:java -Dexec.args="[-CqlToMeasure] [path to CQL library] (id) (encoding) (contentType encoding) (-ip=initial population criteria) {-nX=numerator X criteria} {-dX=denominator X criteria} {sX=stratifier X criteria} (org.opencds.cqf.qdm.output directory path)"
            - NOTE: The 'X' for the numerator, denominator and stratifier params MUST be an Integer value
                - If the numerator, denominator and stratifier params are not provided, the following criteria naming conventions MUST be followed:
                    - initial population: "Initial Population"
                    - numerator: "Numerator X", where X is an Integer (if there is only one numerator expression the X may be omitted)
                    - denominator: "Denominator X", where X is an Integer (if there is only one denominator expression the X may be omitted)
                    - stratifier: "Stratifier X", where X is an Integer (if there is only one stratifier expression the X may be omitted)
            - Example: TODO
            - This tooling converts a CQL library to a FHIR Measure resource
                - Additionally, a FHIR Library resource will also be generated
                - Output will be a transaction Bundle that includes the Measure and Library resources
            - The following elements will be populated in the FHIR Measure resource:
                - id (auto-generated by default)
                - status (draft by default)
                - group.identifier
                - group.population.code
                - group.population.criteria
            - See the documentation for CQL to FHIR Library conversion to see which Library elements will be populated

        - Refresh Measure(s)
            - command: mvn exec:java -Dexec.args="[-RefreshStu3Measure|RefreshR4Measure] [-ptm| pathToMeasures] [-ptl|pathToLibraries] (-e|encoding) (-o|-output)"

        - Refresh Library
            - command: mvn exec:java -Dexec.args"-RefreshLibrary -ini -fv|fhir-version -lp|libraryPath
            - Example: mvn exec:java -Dexec.args="-RefreshLibrary -ini=C:\Users\Bryn\Documents\Src\HL7\sample-ig\ig.ini -fv=fhir4 -lp=C:\Users\Bryn\Documents\Src\HL7\sample-ig\input\examples\Library-example.json"

        - Bundle Resources
            - mvn exec:java -Dexec.args="[-BundleResources] [-pathtodirectory | -ptd] (-outputpath | -op) (-version | -v) (-encoding | -e) (-bundleid | -bid) "
            - Example: mvn exec:java -Dexec.args="-BundleResources -ptd=/Users/adam/Src/cqframework/opioid-cds-r4/quickstartcontent -op=/Users/adam/Src/cqframework/opioid-cds-r4/quickstartcontentbundle -v=r4"
            - This Operation consolidates all resources from files in the 'pathtodirectory' directory into a single FHIR Bundle with
            - an ID that is the value specified in the 'bunldeid' argument and outputs that generated bundle in file format
            - of the type specified by the 'encoding' argument to the 'outputpath' directory.
            - Arguments:
            -   [-pathtodirectory | -ptd] - Path to the directory containing the resource files to be consolidated into the new bundle
            -   (-outputpath | -op) - The directory path to which the generated Bundle file should be written
            -       Default output path: src/main/resources/org/opencds/cqf/tooling/bundle/output
            -   (-version | -v) - FHIR version { dstu2, stu3, r4 }
            -       Default version: stu3
            -   (-encoding | -e) - The file format to be used for representing the resulting Bundle { json, xml }
            -       Default Value: json
            -   (-bundleid | -bid) - A valid FHIR ID to be used as the ID for the resulting FHIR Bundle. The Publisher
            -       validation for Bundle requires a Bundle to have an ID. If no ID is provided, the output Bundle
            -       will not have an ID value.


        - Bundle consolidation
            - mvn exec:java -Dexec.args="[-BundlesToBundle] [input directory path] (output encoding) (output file name) (org.opencds.cqf.qdm.output directory path)"
            - Example: mvn exec:java -Dexec.args="-BundlesToBundle /Users/christopherschuler/Documents/workspace/Bundles xml master-bundle /Users/christopherschuler/Documents/workspace/master-bundles"
            - This tooling consolidates several FHIR Bundles into a single Bundle
            - Accepts Bundles with .json or .xml extensions

        - Bundle decomposition
            - mvn exec:java -Dexec.args="[-BundleToResources] [Bundle file path] (output encoding)
            - Example: mvn exec:java -Dexec.args="-BundleToResources /Users/christopherschuler/Documents/workspace/Bundles/bundle-1.json json /Users/christopherschuler/Documents/workspace/resources"
            - This tooling decomposes a Bundle entry into separate resource files
            - Accepts Bundles with .json or .xml extensions
            
        - MAT Bundle extraction
        	- mvn exec:java -Dexec.args="[-ExtractMatBundle] [Bundle file path] (-v)
        	- Example: mvn exec:java -Dexec.args="-ExtractMatBundle /Users/mholck/Development/ecqm-content-r4/bundles/mat/EXM124/EXM124.json -v=r4"
        	- This tooling extracts out the resources and CQL from a MAT export bundle and puts them in the appropriate directories
        	- Accepts Bundles with .json or .xml extensions
        	- version = FHIR version { stu3, r4 }
                Default version: r4

        - Generate StructureDefinitions from ModelInfo
            - command: mvn exec:java -Dexec.args="[-GenerateSDs] [path to modelinfo xml] (-outputpath | -op) (-encoding | -e)"
            - TODO

        - Generate ModelInfo from StructureDefinitions
             - See documentation in StructureDefinitionToModelInfo for argument documentation and examples

        - Extension builder
            - TODO

        - JsonSchema Generator
            - This tooling converts minimized FHIR profiles into JsonSchemas for form rendering (STU3 and DSTU2 support)
            - mvn exec:java -Dexec.args="-JsonSchemaGenerator"

        - Accelerator Kit Processor
            - This tooling converts a WHO accelerator kit data dictionary to a set of profiles, questionnaires, plan definitions, and libraries
            - mvn exec:java -Dexec.args="-ProcessAcceleratorKit [-pathtospreadsheet | -pts] [-dataelementpages | -dep] (-outputpath | -op) (-encoding | -e)"
            - Example: mvn exec:java -Dexec.args="-ProcessAcceleratorKit -pts=ANC-Primary-Data-Dictionary.xlsx -dep=""ANC Reg,Quick Check,Profile,S&F,PE,Tests,C&T"""

        - Decision Table Processor
            - This tooling converts a WHO accelerator kit decision table to a set of PlanDefinition resources
            - mvn exec:java -Dexec.args="-ProcessDecisionTable [-pathtospreadsheet | -pts] [-decisiontablepages | -dtp] (-outputpath | -op) (-encoding | -e)"
            - Example: mvn exec:java -Dexec.args="-ProcessDecisionTable -pts=ANC-Decision-Logic.xlsx -dtp=""ANC.DT.01 Danger signs,ANC.DT.02 Check symptoms"""

        - Jurisdiction List Converter
            - This tooling converts an RCKMS list of jurisdictions from an Excel file to a CodeSystem
            - mvn exec:java -Dexec.args="-JurisdictionListConverter [-pathtosource | -pts] (outputpath | -op)"
            - Example: mvn exec:java -Dexec.args="-JurisdictionsXlsxToCodeSystem -pts=C:/Users/me/aphl-ig/input/vocabulary/codesystem/sources/rckms-jurisdictions.xlsx -op=C:/Users/me/aphl-ig/input/vocabulary/codesystem"
            
        - Measure Test
            - Executes a particular Measure Test case
            -
            - Example: mvn exec: java -Dexec.args="-ExecuteMeasureTest -test-path /home/jp/repos/connectathon/fhir401/input/tests/EXM104-9.1.000/tests-numer-EXM104-bundle.json -content-path /home/jp/repos/connectathon/fhir401/input/bundles/EXM104-9.1.000-bundle.json -fhir-server http://192.168.2.194:8082/cqf-ruler-r4/fhir"

        - SpreadsheetToCQL
            - command: mvn exec:java -Dexec.args="[-SpreadsheetToCQL] [-pathtospreadsheet | -pts] (-outputpath | -op)"
            - This tooling converts an Excel spreadsheet (.xlsx extension) to a CQL expression with the data from the spreadsheet rows
        */

public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("cqf-tooling version: " + Main.class.getPackage().getImplementationVersion());
            System.out.println("Requests must include which operation to run as a command line argument. See docs for examples on how to use this project.");
            return;
        }

        String operation = args[0];
        if (!operation.startsWith("-")) {
            throw new IllegalArgumentException("Invalid operation: " + operation);
        }

        OperationFactory.createOperation(operation.substring(1)).execute(args);
    }
}
