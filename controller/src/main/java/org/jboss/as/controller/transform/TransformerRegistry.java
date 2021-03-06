package org.jboss.as.controller.transform;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.LegacyResourceDefinition;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author <a href="mailto:tomaz.cerar@redhat.com">Tomaz Cerar</a>
 */
public final class TransformerRegistry {
    private static final Logger log = Logger.getLogger(TransformerRegistry.class);
    private final ConcurrentHashMap<String, List<SubsystemTransformer>> subsystemTransformers = new ConcurrentHashMap<String, List<SubsystemTransformer>>();
    private static TransformerRegistry INSTANCE;
    private final SimpleFullModelTransformer modelTransformer;
    private final ExtensionRegistry extensionRegistry;

    private TransformerRegistry(final ExtensionRegistry extensionRegistry) {
        this.modelTransformer = new SimpleFullModelTransformer(extensionRegistry);
        this.extensionRegistry = extensionRegistry;
        INSTANCE = this;
    }

    public static TransformerRegistry getInstance() { //todo this is ugly!
        return INSTANCE;
    }

    private static ModelNode getSubsystemDefinitionForVersion(final String subsystemName, int majorVersion, int minorVersion) {
        final String key = new StringBuilder(subsystemName).append("-").append(majorVersion).append(".").append(minorVersion).append(".dmr").toString();
        InputStream is = null;
        try {
            is = TransformerRegistry.class.getResourceAsStream(key);
            if (is == null) {
                return null;
            }
            return ModelNode.fromStream(is);
        } catch (IOException e) {
            log.error("Could not read target definition!", e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    //
                }
            }
        }
        return null;
    }

    public static ResourceDefinition loadSubsystemDefinition(final String subsystemName, int majorVersion, int minorVersion) {
        final ModelNode desc = getSubsystemDefinitionForVersion(subsystemName, majorVersion, minorVersion);
        if (desc == null) {
            return null;
        }
        LegacyResourceDefinition rd = new LegacyResourceDefinition(desc);
        return rd;
    }

    public static Resource modelToResource(final ImmutableManagementResourceRegistration reg, final ModelNode model) {
        return modelToResource(reg, model, false);
    }

    public static Resource modelToResource(final ImmutableManagementResourceRegistration reg, final ModelNode model, boolean includeUndefined) {
        Resource res = Resource.Factory.create();
        ModelNode value = new ModelNode();
        for (String name : reg.getAttributeNames(PathAddress.EMPTY_ADDRESS)) {
            if (includeUndefined) {
                value.get(name).set(model.get(name));
            } else {
                if (model.hasDefined(name)) {
                    value.get(name).set(model.get(name));
                }
            }
        }
        res.writeModel(value);

        for (PathElement path : reg.getChildAddresses(PathAddress.EMPTY_ADDRESS)) {

            ImmutableManagementResourceRegistration sub = reg.getSubModel(PathAddress.pathAddress(path));
            if (path.isWildcard()) {
                ModelNode subModel = model.get(path.getKey());
                if (subModel.isDefined()) {
                    for (Property p : subModel.asPropertyList()) {
                        if (p.getValue().isDefined()) {
                            res.registerChild(PathElement.pathElement(path.getKey(), p.getName()), modelToResource(sub, p.getValue(), includeUndefined));
                        }
                    }
                }
            } else {
                ModelNode subModel = model.get(path.getKeyValuePair());
                if (subModel.isDefined()) {
                    res.registerChild(path, modelToResource(sub, subModel));
                }
            }
        }
        return res;
    }

    public void registerSubsystemTransformer(final String subsystemName, final SubsystemTransformer subsystemModelTransformer) {
        subsystemTransformers.putIfAbsent(subsystemName, new LinkedList<SubsystemTransformer>());
        List<SubsystemTransformer> transformers = subsystemTransformers.get(subsystemName);
        transformers.add(subsystemModelTransformer);

    }

    public Resource getTransformedResource(Resource resource, final ImmutableManagementResourceRegistration resourceRegistration, Map<String, String> subsystemVersions) {
        try {
            return modelTransformer.transformResource(resource, resourceRegistration, subsystemVersions);
        } catch (Exception e) {
            log.error("could not transform", e);
            return resource;
        }
    }

    public Resource getTransformedSubsystemResource(Resource resource, final ImmutableManagementResourceRegistration resourceRegistration,
                                                    final String subsystemName, int majorVersion, int minorVersion) {
        Map<String, String> versions = new HashMap<String, String>();
        versions.put(subsystemName, majorVersion + "." + minorVersion);
        return getTransformedResource(resource, resourceRegistration, versions);

    }

    public SubsystemTransformer getSubsystemTransformer(final String name, final int majorVersion, int minorVersion) {
        List<SubsystemTransformer> transformers = subsystemTransformers.get(name);
        if (transformers == null) { return null; }
        for (SubsystemTransformer t : transformers) {
            if (t.getMajorManagementVersion() == majorVersion && t.getMinorManagementVersion() == minorVersion) {
                return t;
            }
        }
        return null;
    }

    public ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
    }

    public static class Factory {
        public static TransformerRegistry create(ExtensionRegistry extensionRegistry) {
            return new TransformerRegistry(extensionRegistry);
        }
    }
}
