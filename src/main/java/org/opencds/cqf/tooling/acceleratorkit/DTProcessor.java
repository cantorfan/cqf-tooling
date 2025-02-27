package org.opencds.cqf.tooling.acceleratorkit;

import ca.uhn.fhir.context.FhirContext;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.hl7.fhir.r4.model.*;
import org.opencds.cqf.tooling.Operation;
import org.opencds.cqf.tooling.terminology.SpreadsheetHelper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

import static org.opencds.cqf.tooling.utilities.IOUtils.ensurePath;

public class DTProcessor extends Operation {
    private String pathToSpreadsheet; // -pathtospreadsheet (-pts)
    private String encoding = "json"; // -encoding (-e)

    // Decision Tables
    private String decisionTablePages; // -decisiontablepages (-dtp) comma-separated list of the names of pages in the workbook to be processed
    private String decisionTablePagePrefix; // -decisiontablepageprefix (-dtpf) all pages with a name starting with this prefix will be processed

    // Canonical Base
    private String canonicalBase = null;

    private String newLine = System.lineSeparator();
    private Map<String, PlanDefinition> planDefinitions = new LinkedHashMap<String, PlanDefinition>();
    // private Map<String, List<PlanDefinition>> planDefinitionsByActivity = new LinkedHashMap<String, List<PlanDefinition>>();
    private Map<String, Library> libraries = new LinkedHashMap<String, Library>();
    private Map<String, StringBuilder> libraryCQL = new LinkedHashMap<String, StringBuilder>();

    private String activityCodeSystem = "http://fhir.org/guides/who/anc-cds/CodeSystem/activity-codes";
    private Map<String, Coding> activityMap = new LinkedHashMap<String, Coding>();

    @Override
    public void execute(String[] args) {
        setOutputPath("src/main/resources/org/opencds/cqf/tooling/acceleratorkit/output"); // default
        for (String arg : args) {
            if (arg.equals("-ProcessDecisionTables")) continue;
            String[] flagAndValue = arg.split("=");
            if (flagAndValue.length < 2) {
                throw new IllegalArgumentException("Invalid argument: " + arg);
            }
            String flag = flagAndValue[0];
            String value = flagAndValue[1];

            switch (flag.replace("-", "").toLowerCase()) {
                case "outputpath": case "op": setOutputPath(value); break; // -outputpath (-op)
                case "pathtospreadsheet": case "pts": pathToSpreadsheet = value; break; // -pathtospreadsheet (-pts)
                case "encoding": case "e": encoding = value.toLowerCase(); break; // -encoding (-e)
                case "decisiontablepages": case "dtp": decisionTablePages = value; break; // -decisiontablepages (-dtp)
                case "decisiontablepageprefix": case "dtpf": decisionTablePagePrefix = value; break; // -decisiontablepageprefix (-dtpf)
                default: throw new IllegalArgumentException("Unknown flag: " + flag);
            }
        }

        canonicalBase = "http://fhir.org/guides/who/anc-cds";

        if (pathToSpreadsheet == null) {
            throw new IllegalArgumentException("The path to the spreadsheet is required");
        }

        Workbook workbook = SpreadsheetHelper.getWorkbook(pathToSpreadsheet);

        processWorkbook(workbook);
    }

    private void processWorkbook(Workbook workbook) {
        String outputPath = getOutputPath();
        try {
            ensurePath(outputPath);
        }
        catch (IOException e) {
            throw new IllegalArgumentException(String.format("Could not ensure output path: %s", e.getMessage()), e);
        }

        // process workbook
        if (decisionTablePages != null) {
            for (String page : decisionTablePages.split(",")) {
                processDecisionTablePage(workbook, page);
            }
        }

        if (decisionTablePagePrefix != null && !decisionTablePagePrefix.isEmpty()) {
            Iterator<Sheet> sheets = workbook.sheetIterator();
            while (sheets.hasNext()) {
                Sheet sheet = sheets.next();
                if (sheet.getSheetName() != null && sheet.getSheetName().startsWith(decisionTablePagePrefix)) {
                    processDecisionTableSheet(workbook, sheet);
                }
            }
        }

        writePlanDefinitions(outputPath);
        writePlanDefinitionIndex(outputPath);
        writeLibraries(outputPath);
        writeLibraryCQL(outputPath);
    }

    private void processDecisionTablePage(Workbook workbook, String page) {
        Sheet sheet = workbook.getSheet(page);
        if (sheet == null) {
            System.out.println(String.format("Sheet %s not found in the Workbook, so no processing was done.", page));
        }
        else {
            processDecisionTableSheet(workbook, sheet);
        }
    }

    private void processDecisionTableSheet(Workbook workbook, Sheet sheet) {
        /*
        Decision table general format:
        Header rows:
        | Decision ID | <Decision ID> <Decision Title> |
        | Business Rule | <Decision Description> |
        | Trigger | <Workflow Step Reference> |
        | Input(s) | ... | Output | Action | Annotation | Reference |
        | <Condition> | ... | <Action.Description> | <Action.Title> | <Action.TextEquivalent> | <Action.Document> | --> Create a row for each...
         */

        Iterator<Row> it = sheet.rowIterator();

        while (it.hasNext()) {
            Row row = it.next();

            Iterator<Cell> cells = row.cellIterator();
            while (cells.hasNext()) {
                Cell cell = cells.next();
                if (cell.getStringCellValue().toLowerCase().startsWith("decision")) {
                    PlanDefinition planDefinition = processDecisionTable(workbook, it, cells);
                    if (planDefinition != null) {
                        planDefinitions.put(planDefinition.getId(), planDefinition);
                        generateLibrary(planDefinition);
                    }
                    break;
                }
            }
        }
    }

    private Coding getActivityCoding(String activityId) {
        if (activityId == null || activityId.isEmpty()) {
            return null;
        }

        int i = activityId.indexOf(" ");
        if (i <= 1) {
            return null;
        }

        String activityCode = activityId.substring(0, i);
        String activityDisplay = activityId.substring(i + 1);

        if (activityCode.isEmpty() || activityDisplay.isEmpty()) {
            return null;
        }

        Coding activity = activityMap.get(activityCode);

        if (activity == null) {
            activity = new Coding().setCode(activityCode).setSystem(activityCodeSystem).setDisplay(activityDisplay);
            activityMap.put(activityCode, activity);
        }

        return activity;
    }

    private PlanDefinition processDecisionTable(Workbook workbook, Iterator<Row> it, Iterator<Cell> cells) {
        PlanDefinition planDefinition = new PlanDefinition();

        if (!cells.hasNext()) {
            throw new IllegalArgumentException("Expected decision title cell");
        }

        Cell cell = cells.next();
        int headerInfoColumnIndex = cell.getColumnIndex();
        String decisionTitle = cell.getStringCellValue();
        int index = decisionTitle.indexOf(' ');
        if (index < 0) {
            throw new IllegalArgumentException("Expected business rule title of the form '<ID> <Title>'");
        }
        String decisionIdentifier = decisionTitle.substring(0, index);
        // String decisionName = decisionTitle.substring(index + 1);
        String decisionId = decisionIdentifier.replace(".", "");

        planDefinition.setTitle(decisionTitle);

        Identifier planDefinitionIdentifier = new Identifier();
        planDefinitionIdentifier.setUse(Identifier.IdentifierUse.OFFICIAL);
        planDefinitionIdentifier.setValue(decisionIdentifier);
        planDefinition.getIdentifier().add(planDefinitionIdentifier);

        planDefinition.setName(decisionId);
        planDefinition.setId(decisionId);
        planDefinition.setUrl(canonicalBase + "/PlanDefinition/" + decisionId);

        if (!it.hasNext()) {
            throw new IllegalArgumentException("Expected Business Rule row");
        }

        Row row = it.next();

        Cell descriptionCell = row.getCell(headerInfoColumnIndex);
        if (descriptionCell == null) {
            throw new IllegalArgumentException("Expected Business Rule description cell");
        }

        String decisionDescription = descriptionCell.getStringCellValue();
        planDefinition.setDescription(decisionDescription);

        planDefinition.setStatus(Enumerations.PublicationStatus.ACTIVE);
        planDefinition.setDate(java.util.Date.from(Instant.now()));
        planDefinition.setExperimental(false);
        planDefinition.setType(new CodeableConcept().addCoding(new Coding().setSystem("http://terminology.hl7.org/CodeSystem/plan-definition-type").setCode("eca-rule")));

        if (!it.hasNext()) {
            throw new IllegalArgumentException("Expected Trigger row");
        }

        row = it.next();

        Cell triggerCell = row.getCell(headerInfoColumnIndex);
        if (triggerCell == null) {
            throw new IllegalArgumentException("Expected Trigger description cell");
        }

        String triggerName = triggerCell.getStringCellValue();
        PlanDefinition.PlanDefinitionActionComponent action = new PlanDefinition.PlanDefinitionActionComponent();
        planDefinition.getAction().add(action);
        action.setTitle(decisionTitle);

        TriggerDefinition trigger = new TriggerDefinition();
        trigger.setType(TriggerDefinition.TriggerType.NAMEDEVENT);
        trigger.setName(triggerName);
        action.getTrigger().add(trigger);

        Coding activityCoding = getActivityCoding(triggerName);
        if (activityCoding != null) {
            planDefinition.addUseContext(new UsageContext()
                    .setCode(new Coding()
                            .setCode("task")
                            .setSystem("http://terminology.hl7.org/CodeSystem/usage-context-type")
                            .setDisplay("Workflow Task")
                    ).setValue(new CodeableConcept().addCoding(activityCoding)));
        }

        if (!it.hasNext()) {
            throw new IllegalArgumentException("Expected decision table header row");
        }

        row = it.next();

        cells = row.cellIterator();
        int inputColumnIndex = -1;
        int outputColumnIndex = -1;
        int actionColumnIndex = -1;
        int annotationColumnIndex = -1;
        int referenceColumnIndex = -1;
        while (cells.hasNext()) {
            cell = cells.next();
            if (cell.getStringCellValue().toLowerCase().startsWith("input")) {
                inputColumnIndex = cell.getColumnIndex();
            }
            else if (cell.getStringCellValue().toLowerCase().startsWith("output")) {
                outputColumnIndex = cell.getColumnIndex();
            }
            else if (cell.getStringCellValue().toLowerCase().startsWith("action")) {
                actionColumnIndex = cell.getColumnIndex();
            }
            else if (cell.getStringCellValue().toLowerCase().startsWith("annotation")) {
                annotationColumnIndex = cell.getColumnIndex();
            }
            else if (cell.getStringCellValue().toLowerCase().startsWith("reference")) {
                referenceColumnIndex = cell.getColumnIndex();
                break;
            }
        }

        int actionId = 1;
        PlanDefinition.PlanDefinitionActionComponent currentAction = null;
        String currentAnnotationValue = null;
        for (;;) {
            PlanDefinition.PlanDefinitionActionComponent subAction = processAction(it, inputColumnIndex, outputColumnIndex, actionColumnIndex, annotationColumnIndex, actionId, currentAnnotationValue, referenceColumnIndex);
            if (subAction == null) {
                break;
            }

            if (!actionsEqual(currentAction, subAction)) {
                actionId++;
                currentAction = subAction;
                currentAnnotationValue = subAction.getTextEquivalent();
                action.getAction().add(subAction);
            }
            else {
                mergeActions(currentAction, subAction);
            }
        }

        return planDefinition;
    }

    // Merge action conditions as an Or, given that the actions are equal
    private void mergeActions(PlanDefinition.PlanDefinitionActionComponent currentAction, PlanDefinition.PlanDefinitionActionComponent newAction) {
        PlanDefinition.PlanDefinitionActionConditionComponent currentCondition = currentAction.getConditionFirstRep();
        PlanDefinition.PlanDefinitionActionConditionComponent newCondition = newAction.getConditionFirstRep();

        if (currentCondition == null) {
            currentAction.getCondition().add(newCondition);
        }
        else if (newCondition != null) {
            currentCondition.getExpression().setDescription(String.format("(%s)\n  OR (%s)", currentCondition.getExpression().getDescription(), newCondition.getExpression().getDescription()));
        }
    }

    // Returns true if the given actions are equal (i.e. are for the same thing, meaning they have the same title, textEquivalent, description, and subactions)
    private boolean actionsEqual(PlanDefinition.PlanDefinitionActionComponent currentAction, PlanDefinition.PlanDefinitionActionComponent newAction) {
        if (currentAction == null) {
            return false;
        }

        return stringsEqual(currentAction.getTitle(), newAction.getTitle())
                && stringsEqual(currentAction.getTextEquivalent(), newAction.getTextEquivalent())
                && stringsEqual(currentAction.getDescription(), newAction.getDescription())
                && subActionsEqual(currentAction.getAction(), newAction.getAction());
    }

    private boolean stringsEqual(String left, String right) {
        return (left == null && right == null) || (left != null && left.equals(right));
    }

    private boolean subActionsEqual(List<PlanDefinition.PlanDefinitionActionComponent> left, List<PlanDefinition.PlanDefinitionActionComponent> right) {
        if (left == null && right == null) {
            return true;
        }

        if (left != null && right != null) {
            for (int leftIndex = 0; leftIndex < left.size(); leftIndex++) {
                if (leftIndex >= right.size()) {
                    return false;
                }

                if (!actionsEqual(left.get(leftIndex), right.get(leftIndex))) {
                    return false;
                }
            }

            return true;
        }

        // One has a list, the other doesn't
        return false;
    }

    private PlanDefinition.PlanDefinitionActionComponent processAction(Iterator<Row> it, int inputColumnIndex,
           int outputColumnIndex, int actionColumnIndex, int annotationColumnIndex, int actionId, String currentAnnotationValue,
           int referenceColumnIndex) {
        if (it.hasNext()) {
            Row row = it.next();
            Cell cell;
            PlanDefinition.PlanDefinitionActionComponent action = new PlanDefinition.PlanDefinitionActionComponent();

            action.setId(Integer.toString(actionId));

            List<String> conditionValues = new ArrayList<String>();
            for (int inputIndex = inputColumnIndex; inputIndex < outputColumnIndex; inputIndex++) {
                cell = row.getCell(inputIndex);
                if (cell != null) {
                    String inputCondition = cell.getStringCellValue();
                    if (inputCondition != null && !inputCondition.isEmpty() && !inputCondition.equals("") && !inputCondition.toLowerCase().startsWith("decision")) {
                        conditionValues.add(inputCondition);
                    }
                }
            }

            if (conditionValues.size() == 0) {
                // No condition, no action, end of decision table
                return null;
            }

            StringBuilder applicabilityCondition = new StringBuilder();
            if (conditionValues.size() == 1) {
                applicabilityCondition.append(conditionValues.get(0));
            }
            else {
                for (String conditionValue : conditionValues) {
                    if (applicabilityCondition.length() > 0) {
                        applicabilityCondition.append(String.format("\n  AND "));
                    }
                    applicabilityCondition.append("(");
                    applicabilityCondition.append(conditionValue);
                    applicabilityCondition.append(")");
                }
            }

            if (outputColumnIndex >= 0) {
                cell = row.getCell(outputColumnIndex);
                String outputValue = cell.getStringCellValue();
                action.setDescription(outputValue);
            }

            PlanDefinition.PlanDefinitionActionConditionComponent condition = new PlanDefinition.PlanDefinitionActionConditionComponent();
            condition.setKind(PlanDefinition.ActionConditionKind.APPLICABILITY);
            condition.setExpression(new Expression().setLanguage("text/cql-identifier").setDescription(applicabilityCondition.toString()));
            action.getCondition().add(condition);

            List<String> actionValues = new ArrayList<String>();
            for (int actionIndex = actionColumnIndex; actionIndex < annotationColumnIndex; actionIndex++) {
                cell = row.getCell(actionIndex);
                if (cell != null) {
                    String actionValue = cell.getStringCellValue();
                    if (actionValue != null && !actionValue.isEmpty() && !actionValue.equals("")) {
                        actionValues.add(actionValue);
                    }
                }
            }

            if (actionValues.size() == 1) {
                action.setTitle(actionValues.get(0));
            }
            else {
                for (String actionValue : actionValues) {
                    PlanDefinition.PlanDefinitionActionComponent subAction = new PlanDefinition.PlanDefinitionActionComponent();
                    subAction.setTitle(actionValue);
                    action.getAction().add(subAction);
                }
            }

            if (annotationColumnIndex >= 0) {
                cell = row.getCell(annotationColumnIndex);
                if (cell != null) {
                    String annotationValue = cell.getStringCellValue();
                    if (annotationValue != null && !annotationValue.isEmpty() && !annotationValue.equals("")) {
                        currentAnnotationValue = annotationValue;
                    }
                }
            }

            // TODO: Might not want to duplicate this so much?
            action.setTextEquivalent(currentAnnotationValue);

            // TODO: Link this to the RelatedArtifact for References
            if (referenceColumnIndex >= 0) {
                cell = row.getCell(referenceColumnIndex);
                if (cell != null) {
                    // TODO: Should this be set to the reference from the previous line?
                    String referenceValue = cell.getStringCellValue();
                    RelatedArtifact relatedArtifact = new RelatedArtifact();
                    relatedArtifact.setType(RelatedArtifact.RelatedArtifactType.CITATION);
                    relatedArtifact.setLabel(referenceValue);
                    action.getDocumentation().add(relatedArtifact);
                }
            }

            return action;
        }

        return null;
    }

    private void generateLibrary(PlanDefinition planDefinition) {
        String id = planDefinition.getIdElement().getIdPart();

        Library library = new Library();
        library.getIdentifier().add(planDefinition.getIdentifierFirstRep());
        library.setId(id);
        library.setName(planDefinition.getName());
        library.setUrl(canonicalBase + "/Library/" + id);
        library.setTitle(planDefinition.getTitle());
        library.setDescription(planDefinition.getDescription());
        library.addContent((Attachment)new Attachment().setId("ig-loader-" + id + ".cql"));

        planDefinition.getLibrary().add((CanonicalType)new CanonicalType().setValue(library.getUrl()));

        StringBuilder cql = new StringBuilder();
        writeLibraryHeader(cql, library);

        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getActionFirstRep().getAction()) {
            if (action.hasCondition()) {
                writeActionCondition(cql, action);
            }
        }

        libraries.put(id, library);
        libraryCQL.put(id, cql);
    }

    private void writeActionCondition(StringBuilder cql, PlanDefinition.PlanDefinitionActionComponent action) {
        PlanDefinition.PlanDefinitionActionConditionComponent condition = action.getConditionFirstRep();
        if (condition.getExpression().getExpression() == null) {
            condition.getExpression().setExpression(
                    String.format("Should %s", action.hasDescription()
                            ? action.getDescription().replace("\"", "\\\"") : "perform action"));
        }
        cql.append("/*");
        cql.append(newLine);
        cql.append(action.getConditionFirstRep().getExpression().getDescription());
        cql.append(newLine);
        cql.append("*/");
        cql.append(newLine);
        cql.append(String.format("define \"%s\":%n", action.getConditionFirstRep().getExpression().getExpression()));
        cql.append("  false"); // Output false, manual process to convert the pseudo-code to CQL
        cql.append(newLine);
        cql.append(newLine);
    }

    private void writeLibraryHeader(StringBuilder cql, Library library) {
        cql.append("library " + library.getName());
        cql.append(newLine);
        cql.append(newLine);
        cql.append("using FHIR version '4.0.1'");
        cql.append(newLine);
        cql.append(newLine);
        cql.append("include FHIRHelpers version '4.0.1'");
        cql.append(newLine);
        cql.append(newLine);
        cql.append("include ANCConcepts called Cx");
        cql.append(newLine);
        cql.append("include ANCDataElements called PatientData");
        cql.append(newLine);
        cql.append(newLine);
        cql.append("context Patient");
        cql.append(newLine);
        cql.append(newLine);
    }

    private void writeLibraries(String outputPath) {
        if (libraries != null && libraries.size() > 0) {
            for (Library library : libraries.values()) {
                writeResource(outputPath, library);
            }
        }
    }

    private void writeLibraryCQL(String outputPath) {
        if (libraryCQL != null && libraryCQL.size() > 0) {
            for (Map.Entry<String, StringBuilder> entry : libraryCQL.entrySet()) {
                String outputFilePath = outputPath + "/" + entry.getKey() + ".cql";
                try (FileOutputStream writer = new FileOutputStream(outputFilePath)) {
                    writer.write(entry.getValue().toString().getBytes());
                    writer.flush();
                }
                catch (IOException e) {
                    e.printStackTrace();
                    throw new IllegalArgumentException("Error writing CQL: " + entry.getKey());
                }
            }
        }
    }

    private void writePlanDefinitions(String outputPath) {
        if (planDefinitions != null && planDefinitions.size() > 0) {
            for (PlanDefinition planDefinition : planDefinitions.values()) {
                writeResource(outputPath, planDefinition);
            }
        }
    }

    /* Write Methods */
    public void writeResource(String path, Resource resource) {
        String outputFilePath = path + "/" + resource.getResourceType().toString().toLowerCase() + "-" + resource.getIdElement().getIdPart() + "." + encoding;
        try (FileOutputStream writer = new FileOutputStream(outputFilePath)) {
            writer.write(
                    encoding.equals("json")
                            ? FhirContext.forR4().newJsonParser().setPrettyPrint(true).encodeResourceToString(resource).getBytes()
                            : FhirContext.forR4().newXmlParser().setPrettyPrint(true).encodeResourceToString(resource).getBytes()
            );
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
            throw new IllegalArgumentException("Error writing resource: " + resource.getIdElement().getIdPart());
        }
    }

    private String buildPlanDefinitionIndex() {
        StringBuilder index = new StringBuilder();
        index.append("|Decision Table|Description|");
        index.append(newLine);
        index.append("|---|---|");
        index.append(newLine);

        for (PlanDefinition pd : planDefinitions.values()) {
            index.append(String.format("|[%s](PlanDefinition-%s.html)|%s|", pd.getTitle(), pd.getId(), pd.getDescription()));
            index.append(newLine);
        }

        return index.toString();
    }

    public void writePlanDefinitionIndex(String path) {
        String outputFilePath = path + "/PlanDefinitionIndex.md";
        try (FileOutputStream writer = new FileOutputStream(outputFilePath)) {
            writer.write(buildPlanDefinitionIndex().getBytes());
            writer.flush();
        }
        catch (IOException e) {
            e.printStackTrace();
            throw new IllegalArgumentException("Error writing plandefinition index");
        }
    }
}
