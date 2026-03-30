/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     WSO2 LLC - support for WSO2 Micro Integrator Configuration
 */

package org.eclipse.lemminx.extensions.synapse;

import org.eclipse.lemminx.customservice.synapse.resourceFinder.NewProjectResourceFinder;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.RegistryResource;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.Resource;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.ResourceResponse;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.extensions.contentmodel.settings.XMLValidationSettings;
import org.eclipse.lemminx.services.extensions.diagnostics.IDiagnosticsParticipant;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

import org.eclipse.lemminx.dom.DOMAttr;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synapse-specific diagnostic participant that validates MI configuration XML
 * for semantic rules that XSD schemas cannot express.
 */
public class SynapseDiagnosticsParticipant implements IDiagnosticsParticipant {

    private static final Logger LOGGER = Logger.getLogger(SynapseDiagnosticsParticipant.class.getName());

    private static final String SYNAPSE_NS = "http://ws.apache.org/ns/synapse";
    private static final String SOURCE = "synapse";
    private static final String SRC_MAIN_WSO2MI = "src" + java.io.File.separator + "main"
            + java.io.File.separator + "wso2mi";

    private static final Set<String> SYNAPSE_ROOT_ELEMENTS = new HashSet<>(Arrays.asList(
            "api", "proxy", "endpoint", "sequence", "inboundEndpoint", "template",
            "task", "localEntry", "messageStore", "messageProcessor", "registry"
    ));

    private static final Set<String> TERMINAL_MEDIATORS = new HashSet<>(Arrays.asList(
            "respond", "drop", "loopback"
    ));

    private static final Set<String> SEQUENCE_CONTAINERS = new HashSet<>(Arrays.asList(
            "inSequence", "outSequence", "faultSequence", "sequence", "then", "else",
            "case", "default", "onComplete"
    ));

    /**
     * Regex to extract variable names from vars.X references in Synapse expressions.
     * Matches: vars.someName, vars["someName"], vars['someName']
     */
    private static final Pattern VARS_REF_PATTERN = Pattern.compile(
            "vars\\.([a-zA-Z_][a-zA-Z_0-9-]*)" +
            "|vars\\[\"([^\"]+)\"\\]" +
            "|vars\\['([^']+)'\\]"
    );

    /**
     * Regex to extract ${...} expressions from attribute values.
     */
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    /**
     * Elements whose 'key' attribute references a named artifact (endpoint, sequence, etc.).
     */
    private static final Set<String> KEY_REF_ELEMENTS = new HashSet<>(Arrays.asList(
            "endpoint", "sequence"
    ));

    /**
     * Elements whose 'onError' attribute references a named sequence.
     */
    private static final Set<String> ON_ERROR_ELEMENTS = new HashSet<>(Arrays.asList(
            "api", "proxy", "sequence", "inboundEndpoint", "resource"
    ));

    @Override
    public void doDiagnostics(DOMDocument xmlDocument, List<Diagnostic> diagnostics,
                              XMLValidationSettings validationSettings, CancelChecker cancelChecker) {
        DOMElement root = xmlDocument.getDocumentElement();
        if (root == null) {
            return;
        }

        // Check if this is a Synapse XML file
        String rootName = root.getLocalName();
        String namespace = root.getNamespaceURI();

        if (SYNAPSE_NS.equals(namespace)) {
            // Valid Synapse file — run all validations
            Set<String> definedVariables = new HashSet<>();
            Set<String> knownArtifacts = buildArtifactNameIndex(xmlDocument);
            validateElement(root, diagnostics, xmlDocument, definedVariables, knownArtifacts);
        } else if (rootName != null && SYNAPSE_ROOT_ELEMENTS.contains(rootName) && namespace == null) {
            // Looks like a Synapse file but missing namespace (Issue 13)
            Range range = XMLPositionUtility.selectStartTagName(root);
            if (range != null) {
                addDiagnostic(diagnostics, range,
                        "Synapse namespace 'http://ws.apache.org/ns/synapse' is missing on root element '" +
                                rootName + "'. XML validation is disabled without the correct namespace. " +
                                "Add xmlns=\"http://ws.apache.org/ns/synapse\" to enable validation.",
                        DiagnosticSeverity.Warning, "MissingSynapseNamespace");
            }
        }
    }

    private void validateElement(DOMNode node, List<Diagnostic> diagnostics, DOMDocument document,
                                Set<String> definedVariables, Set<String> knownArtifacts) {
        if (!(node instanceof DOMElement)) {
            return;
        }
        DOMElement element = (DOMElement) node;
        String name = element.getLocalName();
        if (name == null) {
            return;
        }

        // Track variable definitions BEFORE checking references in this element
        collectVariableDefinition(element, definedVariables);

        // Validate variable references in expression attributes
        validateVariableReferences(element, diagnostics, document, definedVariables);

        // Cross-file reference validation
        if (knownArtifacts != null) {
            validateCrossReferences(element, diagnostics, knownArtifacts);
        }

        switch (name) {
            case "resource":
                validateAPIResource(element, diagnostics, document);
                break;
            case "filter":
                validateFilterMediator(element, diagnostics, document);
                break;
            case "property":
                validatePropertyMediator(element, diagnostics, document);
                break;
            case "header":
                validateHeaderMediator(element, diagnostics, document);
                break;
            case "log":
                validateLogMediator(element, diagnostics, document);
                break;
            case "switch":
                validateSwitchMediator(element, diagnostics, document);
                break;
            case "inboundEndpoint":
                validateInboundEndpoint(element, diagnostics, document);
                break;
            case "source":
                validateEnrichSource(element, diagnostics, document);
                break;
        }

        // Check for unreachable code in sequence containers
        if (SEQUENCE_CONTAINERS.contains(name)) {
            validateUnreachableCode(element, diagnostics, document);
        }

        // Recurse into children
        List<DOMNode> children = element.getChildren();
        if (children != null) {
            for (DOMNode child : children) {
                validateElement(child, diagnostics, document, definedVariables, knownArtifacts);
            }
        }
    }

    /**
     * Issue 2b: API Resource must have either uri-template or url-mapping.
     */
    private void validateAPIResource(DOMElement element, List<Diagnostic> diagnostics, DOMDocument document) {
        // Only validate if parent is <api>
        DOMNode parent = element.getParentNode();
        if (parent == null || !(parent instanceof DOMElement) ||
                !"api".equals(((DOMElement) parent).getLocalName())) {
            return;
        }
        String uriTemplate = element.getAttribute("uri-template");
        String urlMapping = element.getAttribute("url-mapping");
        if (uriTemplate == null && urlMapping == null) {
            Range range = XMLPositionUtility.selectStartTagName(element);
            if (range != null) {
                addDiagnostic(diagnostics, range,
                        "API resource must have either 'uri-template' or 'url-mapping' attribute.",
                        DiagnosticSeverity.Error, "ResourceMissingUriTemplateOrUrlMapping");
            }
        }
    }

    /**
     * Issue 2c: Filter mediator must have (source + regex) or xpath.
     */
    private void validateFilterMediator(DOMElement element, List<Diagnostic> diagnostics, DOMDocument document) {
        String source = element.getAttribute("source");
        String regex = element.getAttribute("regex");
        String xpath = element.getAttribute("xpath");

        boolean hasSourceRegex = source != null && regex != null;
        boolean hasXpath = xpath != null;

        if (!hasSourceRegex && !hasXpath) {
            Range range = XMLPositionUtility.selectStartTagName(element);
            if (range != null) {
                addDiagnostic(diagnostics, range,
                        "Filter mediator requires a condition: either both 'source' and 'regex' attributes, " +
                                "or an 'xpath' attribute.",
                        DiagnosticSeverity.Error, "FilterMissingCondition");
            }
        }
    }

    /**
     * Issue 3: Property mediator with action=set (or default) needs value or expression.
     */
    private void validatePropertyMediator(DOMElement element, List<Diagnostic> diagnostics, DOMDocument document) {
        // Skip if this is a <property> inside <log> (those are log properties, not the property mediator)
        DOMNode parent = element.getParentNode();
        if (parent instanceof DOMElement && "log".equals(((DOMElement) parent).getLocalName())) {
            return;
        }

        String action = element.getAttribute("action");
        // Default action is "set"
        if (action == null || "set".equals(action)) {
            String value = element.getAttribute("value");
            String expression = element.getAttribute("expression");
            if (value == null && expression == null) {
                // Check if there's inline XML content (child elements)
                boolean hasChildElements = hasChildElements(element);
                if (!hasChildElements) {
                    String propName = element.getAttribute("name");
                    Range range = XMLPositionUtility.selectStartTagName(element);
                    if (range != null) {
                        addDiagnostic(diagnostics, range,
                                "Property" + (propName != null ? " '" + propName + "'" : "") +
                                        " with action 'set' requires a 'value' or 'expression' attribute.",
                                DiagnosticSeverity.Warning, "PropertySetMissingValue");
                    }
                }
            }
        }
    }

    /**
     * Issue 3: Header mediator with action=set (or default) needs value or expression.
     */
    private void validateHeaderMediator(DOMElement element, List<Diagnostic> diagnostics, DOMDocument document) {
        String action = element.getAttribute("action");
        if (action == null || "set".equals(action)) {
            String value = element.getAttribute("value");
            String expression = element.getAttribute("expression");
            if (value == null && expression == null) {
                boolean hasChildElements = hasChildElements(element);
                if (!hasChildElements) {
                    String headerName = element.getAttribute("name");
                    Range range = XMLPositionUtility.selectStartTagName(element);
                    if (range != null) {
                        addDiagnostic(diagnostics, range,
                                "Header" + (headerName != null ? " '" + headerName + "'" : "") +
                                        " with action 'set' requires a 'value' or 'expression' attribute, " +
                                        "or an inline XML child element.",
                                DiagnosticSeverity.Warning, "HeaderSetMissingValue");
                    }
                }
            }
        }
    }

    /**
     * Issue 3: Log with level="custom" should have at least one property child.
     */
    private void validateLogMediator(DOMElement element, List<Diagnostic> diagnostics, DOMDocument document) {
        String level = element.getAttribute("level");
        if ("custom".equals(level)) {
            boolean hasPropertyChild = false;
            List<DOMNode> children = element.getChildren();
            if (children != null) {
                for (DOMNode child : children) {
                    if (child instanceof DOMElement && "property".equals(((DOMElement) child).getLocalName())) {
                        hasPropertyChild = true;
                        break;
                    }
                }
            }
            if (!hasPropertyChild) {
                Range range = XMLPositionUtility.selectStartTagName(element);
                if (range != null) {
                    addDiagnostic(diagnostics, range,
                            "Log mediator with level='custom' should have at least one <property> child element.",
                            DiagnosticSeverity.Warning, "LogCustomMissingProperties");
                }
            }
        }
    }

    /**
     * Issue 3: Switch mediator should not have duplicate case regex values.
     */
    private void validateSwitchMediator(DOMElement element, List<Diagnostic> diagnostics, DOMDocument document) {
        Set<String> seenRegex = new HashSet<>();
        List<DOMNode> children = element.getChildren();
        if (children == null) return;
        for (DOMNode child : children) {
            if (child instanceof DOMElement && "case".equals(((DOMElement) child).getLocalName())) {
                String regex = ((DOMElement) child).getAttribute("regex");
                if (regex != null && !seenRegex.add(regex)) {
                    Range range = XMLPositionUtility.selectStartTagName((DOMElement) child);
                    if (range != null) {
                        addDiagnostic(diagnostics, range,
                                "Duplicate switch case: regex '" + regex + "' already exists in this switch mediator.",
                                DiagnosticSeverity.Warning, "DuplicateSwitchCase");
                    }
                }
            }
        }
    }

    /**
     * Issue 12: InboundEndpoint must have either protocol or class.
     */
    private void validateInboundEndpoint(DOMElement element, List<Diagnostic> diagnostics, DOMDocument document) {
        String protocol = element.getAttribute("protocol");
        String clazz = element.getAttribute("class");
        if (protocol == null && clazz == null) {
            Range range = XMLPositionUtility.selectStartTagName(element);
            if (range != null) {
                addDiagnostic(diagnostics, range,
                        "Inbound endpoint must have either 'protocol' or 'class' attribute.",
                        DiagnosticSeverity.Error, "InboundMissingProtocolOrClass");
            }
        }
    }

    /**
     * Enrich source: type="custom" requires xpath, type="property" requires property attr,
     * type="inline" requires inline XML child content.
     */
    private void validateEnrichSource(DOMElement element, List<Diagnostic> diagnostics, DOMDocument document) {
        // Only validate <source> inside <enrich>
        DOMNode parent = element.getParentNode();
        if (parent == null || !(parent instanceof DOMElement) ||
                !"enrich".equals(((DOMElement) parent).getLocalName())) {
            return;
        }

        String type = element.getAttribute("type");
        // Default type is "custom"
        if (type == null) {
            type = "custom";
        }

        Range range = XMLPositionUtility.selectStartTagName(element);
        if (range == null) {
            return;
        }

        switch (type) {
            case "custom":
                if (element.getAttribute("xpath") == null) {
                    addDiagnostic(diagnostics, range,
                            "Enrich source with type='custom' requires an 'xpath' attribute.",
                            DiagnosticSeverity.Error, "EnrichSourceCustomMissingXpath");
                }
                break;
            case "property":
                if (element.getAttribute("property") == null) {
                    addDiagnostic(diagnostics, range,
                            "Enrich source with type='property' requires a 'property' attribute.",
                            DiagnosticSeverity.Error, "EnrichSourcePropertyMissingProperty");
                }
                break;
            case "inline":
                if (!hasChildElements(element)) {
                    addDiagnostic(diagnostics, range,
                            "Enrich source with type='inline' requires inline XML content.",
                            DiagnosticSeverity.Error, "EnrichSourceInlineMissingContent");
                }
                break;
        }
    }

    /**
     * Issue 7: Detect unreachable code after terminal mediators (respond, drop, loopback).
     */
    private void validateUnreachableCode(DOMElement container, List<Diagnostic> diagnostics, DOMDocument document) {
        List<DOMNode> children = container.getChildren();
        if (children == null) return;

        String terminalMediatorName = null;
        boolean afterTerminal = false;

        for (DOMNode child : children) {
            if (!(child instanceof DOMElement)) continue;
            DOMElement childElement = (DOMElement) child;
            String childName = childElement.getLocalName();
            if (childName == null) continue;

            if (afterTerminal) {
                Range range = XMLPositionUtility.selectStartTagName(childElement);
                if (range != null) {
                    addDiagnostic(diagnostics, range,
                            "Unreachable mediator: this '" + childName +
                                    "' will never execute because it follows a '" + terminalMediatorName +
                                    "' mediator.",
                            DiagnosticSeverity.Warning, "UnreachableCode");
                }
            } else if (TERMINAL_MEDIATORS.contains(childName)) {
                afterTerminal = true;
                terminalMediatorName = childName;
            }
        }
    }

    /**
     * Issue 1c: Collect variable definitions from <variable> and <property scope="default"> mediators.
     */
    private void collectVariableDefinition(DOMElement element, Set<String> definedVariables) {
        String name = element.getLocalName();
        if ("variable".equals(name)) {
            String varName = element.getAttribute("name");
            String action = element.getAttribute("action");
            // action "set" or absent means variable is being defined
            if (varName != null && (action == null || "set".equals(action))) {
                definedVariables.add(varName);
            }
        } else if ("property".equals(name)) {
            // <property> with scope="default" (or no scope, which defaults to "synapse")
            // defines a variable accessible via vars.X
            String scope = element.getAttribute("scope");
            String action = element.getAttribute("action");
            if ("default".equals(scope) && (action == null || "set".equals(action))) {
                String varName = element.getAttribute("name");
                if (varName != null) {
                    definedVariables.add(varName);
                }
            }
        } else if ("foreach".equals(name) || "iterate".equals(name)) {
            // foreach/iterate implicitly define a loop counter variable
            // and the collection item is accessible in the flow
        }
    }

    /**
     * Issue 1c: Validate that vars.X references in expression attributes refer to defined variables.
     */
    private void validateVariableReferences(DOMElement element, List<Diagnostic> diagnostics,
                                            DOMDocument document, Set<String> definedVariables) {
        // Check all attributes for ${...} expressions containing vars.X
        List<DOMAttr> attrs = element.getAttributeNodes();
        if (attrs == null) {
            return;
        }
        for (DOMAttr attr : attrs) {
            String attrValue = attr.getValue();
            if (attrValue == null || !attrValue.contains("vars.") && !attrValue.contains("vars[")) {
                continue;
            }

            // Extract ${...} expressions from the attribute value
            Matcher exprMatcher = EXPRESSION_PATTERN.matcher(attrValue);
            while (exprMatcher.find()) {
                String exprContent = exprMatcher.group(1);

                // Find vars.X references within the expression
                Matcher varsMatcher = VARS_REF_PATTERN.matcher(exprContent);
                while (varsMatcher.find()) {
                    // Get the variable name from whichever group matched
                    String varName = varsMatcher.group(1);
                    if (varName == null) varName = varsMatcher.group(2);
                    if (varName == null) varName = varsMatcher.group(3);

                    if (varName != null && !definedVariables.contains(varName)) {
                        Range range = XMLPositionUtility.selectAttributeValue(attr);
                        if (range != null) {
                            addDiagnostic(diagnostics, range,
                                    "Variable '" + varName + "' is referenced but not defined in the preceding " +
                                            "mediation flow. Define it using <variable name=\"" + varName +
                                            "\" .../> before this point.",
                                    DiagnosticSeverity.Warning, "UndefinedVariable");
                        }
                    }
                }
            }
        }
    }

    /**
     * Issue 6: Validate cross-file references (key, target, onError attributes).
     */
    private void validateCrossReferences(DOMElement element, List<Diagnostic> diagnostics,
                                         Set<String> knownArtifacts) {
        String name = element.getLocalName();
        if (name == null) {
            return;
        }

        // Check 'key' attribute on endpoint and sequence elements (inside call/send mediators)
        if (KEY_REF_ELEMENTS.contains(name)) {
            String key = element.getAttribute("key");
            if (key != null && !key.isEmpty() && !isExpression(key) && !knownArtifacts.contains(key)) {
                DOMAttr keyAttr = element.getAttributeNode("key");
                if (keyAttr != null) {
                    Range range = XMLPositionUtility.selectAttributeValue(keyAttr);
                    if (range != null) {
                        addDiagnostic(diagnostics, range,
                                "Referenced " + name + " '" + key + "' not found in the project. " +
                                        "Ensure the artifact exists or check for typos.",
                                DiagnosticSeverity.Warning, "UnresolvedArtifactReference");
                    }
                }
            }
        }

        // Check 'target' attribute on call-template
        if ("call-template".equals(name)) {
            String target = element.getAttribute("target");
            if (target != null && !target.isEmpty() && !isExpression(target)
                    && !knownArtifacts.contains(target)) {
                DOMAttr targetAttr = element.getAttributeNode("target");
                if (targetAttr != null) {
                    Range range = XMLPositionUtility.selectAttributeValue(targetAttr);
                    if (range != null) {
                        addDiagnostic(diagnostics, range,
                                "Referenced template '" + target + "' not found in the project. " +
                                        "Ensure the template exists or check for typos.",
                                DiagnosticSeverity.Warning, "UnresolvedArtifactReference");
                    }
                }
            }
        }

        // Check 'onError' attribute
        if (ON_ERROR_ELEMENTS.contains(name)) {
            String onError = element.getAttribute("onError");
            if (onError != null && !onError.isEmpty() && !isExpression(onError)
                    && !knownArtifacts.contains(onError)) {
                DOMAttr onErrorAttr = element.getAttributeNode("onError");
                if (onErrorAttr != null) {
                    Range range = XMLPositionUtility.selectAttributeValue(onErrorAttr);
                    if (range != null) {
                        addDiagnostic(diagnostics, range,
                                "Referenced error sequence '" + onError + "' not found in the project. " +
                                        "Ensure the sequence exists or check for typos.",
                                DiagnosticSeverity.Warning, "UnresolvedArtifactReference");
                    }
                }
            }
        }

        // Check 'key' attribute on xslt and xquery mediators (registry references)
        if ("xslt".equals(name) || "xquery".equals(name)) {
            validateRegistryKeyRef(element, "key", name + " stylesheet", diagnostics, knownArtifacts);
        }

        // Check datamapper config, inputSchema, outputSchema attributes
        if ("datamapper".equals(name)) {
            validateRegistryKeyRef(element, "config", "datamapper configuration", diagnostics, knownArtifacts);
            validateRegistryKeyRef(element, "inputSchema", "datamapper input schema", diagnostics, knownArtifacts);
            validateRegistryKeyRef(element, "outputSchema", "datamapper output schema", diagnostics, knownArtifacts);
            validateRegistryKeyRef(element, "xsltStyleSheet", "datamapper XSLT stylesheet", diagnostics,
                    knownArtifacts);
        }
    }

    /**
     * Validates a registry key reference attribute against the known artifacts index.
     */
    private void validateRegistryKeyRef(DOMElement element, String attrName, String description,
                                        List<Diagnostic> diagnostics, Set<String> knownArtifacts) {
        String value = element.getAttribute(attrName);
        if (value == null || value.isEmpty() || isExpression(value)) {
            return;
        }
        if (!knownArtifacts.contains(value)) {
            DOMAttr attr = element.getAttributeNode(attrName);
            if (attr != null) {
                Range range = XMLPositionUtility.selectAttributeValue(attr);
                if (range != null) {
                    addDiagnostic(diagnostics, range,
                            "Referenced " + description + " '" + value + "' not found in the project. " +
                                    "Ensure the resource exists or check for typos.",
                            DiagnosticSeverity.Warning, "UnresolvedRegistryReference");
                }
            }
        }
    }

    /**
     * Checks if a string is a Synapse expression (${...} or {${...}}).
     */
    private boolean isExpression(String value) {
        return (value.startsWith("${") && value.endsWith("}"))
                || (value.startsWith("{${") && value.endsWith("}}"));
    }

    /**
     * Builds a set of all known artifact names and registry keys in the project.
     * Returns null if the project path cannot be determined.
     */
    private Set<String> buildArtifactNameIndex(DOMDocument document) {
        String projectPath = deriveProjectPath(document);
        if (projectPath == null) {
            return null;
        }

        Set<String> artifactNames = new HashSet<>();
        try {
            NewProjectResourceFinder resourceFinder = new NewProjectResourceFinder();
            Map<String, ResourceResponse> allResources = resourceFinder.findAllResources(projectPath);

            for (ResourceResponse response : allResources.values()) {
                if (response.getResources() != null) {
                    for (Resource resource : response.getResources()) {
                        if (resource.getName() != null) {
                            artifactNames.add(resource.getName());
                        }
                    }
                }
                // Also collect registry keys for xslt/xquery/datamapper references
                if (response.getRegistryResources() != null) {
                    for (Resource resource : response.getRegistryResources()) {
                        if (resource instanceof RegistryResource) {
                            String regKey = ((RegistryResource) resource).getRegistryKey();
                            if (regKey != null) {
                                artifactNames.add(regKey);
                                // Normalize: add both gov:path and gov:/path variants
                                if (regKey.contains(":") && !regKey.contains(":/")) {
                                    String prefix = regKey.substring(0, regKey.indexOf(':') + 1);
                                    String path = regKey.substring(regKey.indexOf(':') + 1);
                                    artifactNames.add(prefix + "/" + path);
                                } else if (regKey.contains(":/")) {
                                    String normalized = regKey.replace(":/", ":");
                                    artifactNames.add(normalized);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to build artifact name index for cross-reference validation", e);
            return null;
        }
        return artifactNames;
    }

    /**
     * Derives the project root path from the document URI by looking for the
     * src/main/wso2mi path segment.
     */
    private String deriveProjectPath(DOMDocument document) {
        String docUri = document.getDocumentURI();
        if (docUri == null) {
            return null;
        }
        try {
            Path filePath;
            if (docUri.startsWith("file:")) {
                filePath = Paths.get(new URI(docUri));
            } else {
                filePath = Paths.get(docUri);
            }
            String pathStr = filePath.toString();
            int idx = pathStr.indexOf(SRC_MAIN_WSO2MI);
            if (idx > 0) {
                return pathStr.substring(0, idx - 1); // -1 to remove trailing separator
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not derive project path from document URI: " + docUri, e);
        }
        return null;
    }

    private boolean hasChildElements(DOMElement element) {
        List<DOMNode> children = element.getChildren();
        if (children == null) return false;
        for (DOMNode child : children) {
            if (child instanceof DOMElement) {
                return true;
            }
        }
        return false;
    }

    private void addDiagnostic(List<Diagnostic> diagnostics, Range range, String message,
                               DiagnosticSeverity severity, String code) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setRange(range);
        diagnostic.setMessage(message);
        diagnostic.setSeverity(severity);
        diagnostic.setSource(SOURCE);
        diagnostic.setCode(code);
        diagnostics.add(diagnostic);
    }
}
