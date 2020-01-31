package io.digdag.standards.command.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import io.digdag.client.config.Config;
import io.digdag.spi.CommandContext;
import io.digdag.spi.CommandRequest;
import io.digdag.spi.TaskRequest;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeSpec;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DefaultKubernetesClient
        implements KubernetesClient
{
    private static Logger logger = LoggerFactory.getLogger(DefaultKubernetesClient.class);

    private final KubernetesClientConfig config;
    private final io.fabric8.kubernetes.client.DefaultKubernetesClient client;

    public DefaultKubernetesClient(final KubernetesClientConfig config,
            final io.fabric8.kubernetes.client.DefaultKubernetesClient client)
    {
        this.config = config;
        this.client = client;
    }

    @Override
    public KubernetesClientConfig getConfig()
    {
        return config;
    }

    @Override
    public Pod runPod(final CommandContext context, final CommandRequest request,
            final String name, final List<String> commands, final List<String> arguments)
    {
        // If PersistentVolume or PersistentVolumeClaim is set, create PersistentVolume or PersistentVolumeClaim before making pod.
        createPersistentVolume(context);
        createPersistentVolumeClaim(context);

        final Config kubernetesPodConfig = extractTargetKindConfig(context, "Pod");
        final Container container = createContainer(context, request, kubernetesPodConfig, name, commands, arguments);
        final PodSpec podSpec = createPodSpec(context, request, kubernetesPodConfig, container);
        io.fabric8.kubernetes.api.model.Pod pod = client.pods()
                .createNew()
                .withNewMetadata()
                .withName(name)
                .withNamespace(client.getNamespace())
                .withLabels(getPodLabels())
                .endMetadata()
                .withSpec(podSpec)
                .done();
        return Pod.of(pod);
    }

    @Override
    public Pod pollPod(final String podName)
    {
        final io.fabric8.kubernetes.api.model.Pod pod = client.pods()
                .inNamespace(client.getNamespace())
                .withName(podName)
                .get();
        return Pod.of(pod);
    }

    @Override
    public boolean deletePod(final String podName)
    {
        // TODO need to retry?

        // TODO
        // We'd better to consider about pods graceful deletion here.
        //
        // References:
        //   https://kubernetes.io/docs/concepts/workloads/pods/pod/#termination-of-pods
        //   https://kubernetes.io/docs/tasks/run-application/force-delete-stateful-set-pod/
        return client.pods()
                .inNamespace(client.getNamespace())
                .withName(podName)
                .delete();
    }

    @Override
    public boolean isWaitingContainerCreation(final Pod pod)
    {
        boolean isWaitingContainerCreation = pod.getStatus().getContainerStatuses().stream().allMatch(containerStatus -> containerStatus.getState().getWaiting() != null);
        return isWaitingContainerCreation;
    }

    @Override
    public String getLog(final String podName, final long offset)
            throws IOException
    {
        final PodResource podResource = client.pods().withName(podName);
        final Reader reader = podResource.getLogReader(); // return InputStreamReader
        try {
            reader.skip(offset); // skip the chars that were already read
            return CharStreams.toString(reader); // TODO not use String object
        }
        finally {
            reader.close();
        }
    }

    protected Map<String, String> getPodLabels()
    {
        return ImmutableMap.of();
    }

    @VisibleForTesting
    Container createContainer(final CommandContext context, final CommandRequest request,
            final Config kubernetesPodConfig, final String name, final List<String> commands, final List<String> arguments)
    {
        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(getContainerImage(context, request))
                .withEnv(toEnvVars(getEnvironments(context, request)))
                .withResources(getResources(kubernetesPodConfig))
                .withVolumeMounts(getVolumeMounts(kubernetesPodConfig))
                .withCommand(commands)
                .withArgs(arguments)
                .build();
        return container;
    }

    @VisibleForTesting
    PodSpec createPodSpec(final CommandContext context, final CommandRequest request,
            final Config kubernetesPodConfig, final Container container)
    {
        // TODO
        // Revisit what values should be extracted as config params or system config params
        PodSpec podSpec =  new PodSpecBuilder()
                //.withHostNetwork(true);
                //.withDnsPolicy("ClusterFirstWithHostNet");
                .addToContainers(container)
                .withAffinity(getAffinity(kubernetesPodConfig))
                .withTolerations(getTolerations(kubernetesPodConfig))
                .withVolumes(getVolumes(kubernetesPodConfig))
                // TODO extract as config parameter
                // Restart policy is "Never" by default since it needs to avoid executing the operator multiple times. It might not
                // make the script idempotent.
                // https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#restart-policy
                .withRestartPolicy("Never")
                .build();
        return podSpec;
    }

    protected PersistentVolume createPersistentVolume(final CommandContext context)
    {
        final Config kubernetesPvConfig = extractTargetKindConfig(context, "PersistentVolume");
        if (kubernetesPvConfig != null && kubernetesPvConfig.has("spec"))
            return client.persistentVolumes()
                .createOrReplaceWithNew()
                .withNewMetadata()
                .withName(kubernetesPvConfig.get("name", String.class))
                .withNamespace(client.getNamespace())
                .endMetadata()
                .withSpec(getPersistentVolume(kubernetesPvConfig.get("spec", Config.class)))
                .done();
        else
            return null;
    }

    protected PersistentVolumeClaim createPersistentVolumeClaim(final CommandContext context)
    {
        final Config kubernetesPvcConfig = extractTargetKindConfig(context, "PersistentVolumeClaim");
        if (kubernetesPvcConfig != null && kubernetesPvcConfig.has("spec"))
            return client.persistentVolumeClaims()
                .createOrReplaceWithNew()
                .withNewMetadata()
                .withName(kubernetesPvcConfig.get("name", String.class))
                .withNamespace(client.getNamespace())
                .endMetadata()
                .withSpec(getPersistentVolumeClaim(kubernetesPvcConfig.get("spec", Config.class)))
                .done();
        else
            return null;
    }

    protected Config extractTargetKindConfig(final CommandContext context, final String kind) {
        final TaskRequest taskRequest = context.getTaskRequest();
        final Config kubernetesConfig = taskRequest.getConfig().get("kubernetes", Config.class);
        Config kubernetesTargetKindConfig = null;
        if (kubernetesConfig != null && kubernetesConfig.has(kind)) kubernetesTargetKindConfig = kubernetesConfig.get(kind, Config.class);
        return kubernetesTargetKindConfig;
    }

    @VisibleForTesting
    PersistentVolumeSpec getPersistentVolume(Config kubernetesPvSpecConfig) {
        final JsonNode persistentVolumeSpecNode = kubernetesPvSpecConfig.getInternalObjectNode();
        return Serialization.unmarshal(persistentVolumeSpecNode.toString(), PersistentVolumeSpec.class);
    }

    @VisibleForTesting
    PersistentVolumeClaimSpec getPersistentVolumeClaim(Config kubernetesPvcSpecConfig) {
        final JsonNode persistentVolumeClaimSpecNode = kubernetesPvcSpecConfig.getInternalObjectNode();
        return Serialization.unmarshal(persistentVolumeClaimSpecNode.toString(), PersistentVolumeClaimSpec.class);
    }

    protected ResourceRequirements getResources(Config kubernetesPodConfig) {
        if (kubernetesPodConfig != null && kubernetesPodConfig.has("resources")) {
            final JsonNode resourcesNode = kubernetesPodConfig.getInternalObjectNode().get("resources");
            return Serialization.unmarshal(resourcesNode.toString(), ResourceRequirements.class);
        } else {
            return null;
        }
    }

    protected List<VolumeMount> getVolumeMounts(Config kubernetesPodConfig) {
        if (kubernetesPodConfig != null && kubernetesPodConfig.has("volumeMounts")) {
            final JsonNode volumeMountsNode = kubernetesPodConfig.getInternalObjectNode().get("volumeMounts");
            return convertToResourceList(volumeMountsNode, VolumeMount.class);
        } else {
            return null;
        }
    }

    protected Affinity getAffinity(Config kubernetesPodConfig) {
        if (kubernetesPodConfig != null && kubernetesPodConfig.has("affinity")) {
            final JsonNode affinityNode = kubernetesPodConfig.getInternalObjectNode().get("affinity");
            return Serialization.unmarshal(affinityNode.toString(), Affinity.class);
        } else {
            return null;
        }
    }

    protected List<Toleration> getTolerations(Config kubernetesPodConfig) {
        if (kubernetesPodConfig != null && kubernetesPodConfig.has("tolerations")) {
            final JsonNode tolerationsNode = kubernetesPodConfig.getInternalObjectNode().get("tolerations");
            return convertToResourceList(tolerationsNode, Toleration.class);
        } else {
            return null;
        }
    }

    protected List<Volume> getVolumes(Config kubernetesPodConfig) {
        if (kubernetesPodConfig != null && kubernetesPodConfig.has("volumes")) {
            final JsonNode volumesNode = kubernetesPodConfig.getInternalObjectNode().get("volumes");
            return convertToResourceList(volumesNode, Volume.class);
        } else {
            return null;
        }
    }

    protected <T> List<T> convertToResourceList(final JsonNode node, final Class<T> type)
    {
        List<T> resourcesList = new ArrayList<>();
        if (node.isArray()){
            for (JsonNode resource : node) {
                resourcesList.add(Serialization.unmarshal(resource.toString(), type));
            }
        } else {
            resourcesList.add(Serialization.unmarshal(node.toString(), type));
        }
        return resourcesList;
    }

    protected String getContainerImage(final CommandContext context, final CommandRequest request)
    {
        final Config config = context.getTaskRequest().getConfig();
        final Config dockerConfig = config.getNested("docker");
        return dockerConfig.get("image", String.class);
    }

    protected Map<String, String> getEnvironments(final CommandContext context, final CommandRequest request)
    {
        return request.getEnvironments();
    }

    private static List<EnvVar> toEnvVars(final Map<String, String> environments)
    {
        final ImmutableList.Builder<EnvVar> envVars = ImmutableList.builder();
        for (final Map.Entry<String, String> e : environments.entrySet()) {
            final EnvVar envVar = new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build();
            envVars.add(envVar);
        }
        return envVars.build();
    }

    @Override
    public void close()
    {
        if (client != null) {
            client.close();
        }
    }
}
