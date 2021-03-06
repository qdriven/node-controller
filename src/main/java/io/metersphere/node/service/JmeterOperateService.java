package io.metersphere.node.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.InvocationBuilder;
import io.metersphere.node.config.JmeterProperties;
import io.metersphere.node.controller.request.TestRequest;
import io.metersphere.node.util.DockerClientService;
import io.metersphere.node.util.LogUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class JmeterOperateService {
    @Resource
    private JmeterProperties jmeterProperties;
    private static String ROOT_PATH;

    static {
        ROOT_PATH = System.getenv("JMETER_DATA_PATH");
        LogUtil.info("JMETER_DATA_PATH: " + ROOT_PATH);
        if (StringUtils.isBlank(ROOT_PATH)) {
            ROOT_PATH = "/opt/metersphere/data/jmeter/";
        }
    }

    public void startContainer(TestRequest testRequest) throws IOException {
        String bootstrapServers = testRequest.getEnv().get("BOOTSTRAP_SERVERS");
        checkKafka(bootstrapServers);

        LogUtil.info("Receive start container request, test id: {}", testRequest.getTestId());
        DockerClient dockerClient = DockerClientService.connectDocker(testRequest);
        String testId = testRequest.getTestId();

        String containerImage = testRequest.getImage();
        String filePath = StringUtils.join(new String[]{ROOT_PATH, testId}, File.separator);
        String fileName = testId + ".jmx";


        //  每个测试生成一个文件夹
        FileUtils.writeStringToFile(new File(filePath + File.separator + fileName), testRequest.getFileString(), StandardCharsets.UTF_8);
        // 保存测试数据文件
        Map<String, String> testData = testRequest.getTestData();
        if (!CollectionUtils.isEmpty(testData)) {
            for (String k : testData.keySet()) {
                String v = testData.get(k);
                FileUtils.writeStringToFile(new File(filePath + File.separator + k), v, StandardCharsets.UTF_8);
            }
        }

        // 保存 byte[] jar
        Map<String, byte[]> jarFiles = testRequest.getTestJars();
        if (!CollectionUtils.isEmpty(jarFiles)) {
            for (String k : jarFiles.keySet()) {
                byte[] v = jarFiles.get(k);
                FileUtils.writeByteArrayToFile(new File(filePath + File.separator + k), v);
            }
        }

        // 查找镜像
        searchImage(dockerClient, testRequest.getImage());
        // 检查容器是否存在
        checkContainerExists(dockerClient, testId);
        // 启动测试
        startContainer(testRequest, dockerClient, testId, containerImage, filePath);
    }

    private void startContainer(TestRequest testRequest, DockerClient dockerClient, String testId, String containerImage, String filePath) {
        // 创建 hostConfig
        HostConfig hostConfig = HostConfig.newHostConfig();
        hostConfig.withBinds(Bind.parse(filePath + ":/test"));

        String[] envs = getEnvs(testRequest);
        String containerId = DockerClientService.createContainers(dockerClient, testId, containerImage, hostConfig, envs).getId();

        DockerClientService.startContainer(dockerClient, containerId);
        LogUtil.info("Container create started containerId: " + containerId);
        dockerClient.waitContainerCmd(containerId)
                .exec(new WaitContainerResultCallback() {
                    @Override
                    public void onComplete() {
                        // 清理文件夹
                        try {
                            FileUtils.forceDelete(new File(filePath));
                            LogUtil.info("Remove dir completed.");
                            if (DockerClientService.existContainer(dockerClient, containerId) > 0) {
                                DockerClientService.removeContainer(dockerClient, containerId);
                            }
                            LogUtil.info("Remove container completed: " + containerId);
                        } catch (Exception e) {
                            LogUtil.error("Remove dir error: ", e);
                        }
                        LogUtil.info("completed....");
                    }
                });

        dockerClient.logContainerCmd(containerId)
                .withFollowStream(true)
                .withStdOut(true)
                .withStdErr(true)
                .withTailAll()
                .exec(new InvocationBuilder.AsyncResultCallback<Frame>() {
                    @Override
                    public void onNext(Frame item) {
                        LogUtil.info(new String(item.getPayload()).trim());
                    }
                });
    }

    private void checkContainerExists(DockerClient dockerClient, String testId) {
        List<Container> list = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withStatusFilter(Arrays.asList("created", "restarting", "running", "paused", "exited"))
                .withNameFilter(Collections.singletonList(testId))
                .exec();
        if (!CollectionUtils.isEmpty(list)) {
            list.forEach(container -> DockerClientService.removeContainer(dockerClient, container.getId()));
        }
    }

    private void checkKafka(String bootstrapServers) {
        String[] servers = StringUtils.split(bootstrapServers, ",");
        try {
            for (String s : servers) {
                String[] ipAndPort = s.split(":");
                //1,建立tcp
                String ip = ipAndPort[0];
                int port = Integer.parseInt(ipAndPort[1]);
                Socket soc = new Socket();
                soc.connect(new InetSocketAddress(ip, port), 1000); // 1s timeout
                //2.输入内容
                String content = "1010";
                byte[] bs = content.getBytes();
                OutputStream os = soc.getOutputStream();
                os.write(bs);
                //3.关闭
                soc.close();
            }
        } catch (Exception e) {
            LogUtil.error(e);
            throw new RuntimeException("Failed to connect to Kafka");
        }
    }

    private String[] getEnvs(TestRequest testRequest) {
        Map<String, String> env = testRequest.getEnv();
        // HEAP
        env.put("HEAP", jmeterProperties.getHeap());
        return env.keySet().stream().map(k -> k + "=" + env.get(k)).toArray(String[]::new);
    }

    private void searchImage(DockerClient dockerClient, String imageName) {
        // image
        List<Image> imageList = dockerClient.listImagesCmd().exec();
        if (CollectionUtils.isEmpty(imageList)) {
            throw new RuntimeException("Image List is empty");
        }
        List<Image> collect = imageList.stream().filter(image -> {
            String[] repoTags = image.getRepoTags();
            if (repoTags == null) {
                return false;
            }
            for (String repoTag : repoTags) {
                if (repoTag.equals(imageName)) {
                    return true;
                }
            }
            return false;
        }).collect(Collectors.toList());

        if (collect.size() == 0) {
            throw new RuntimeException("Image Not Found: " + imageName);
        }
    }


    public void stopContainer(String testId) {
        LogUtil.info("Receive stop container request, test: {}", testId);
        DockerClient dockerClient = DockerClientService.connectDocker();

        // container filter
        List<Container> list = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withStatusFilter(Collections.singletonList("running"))
                .withNameFilter(Collections.singletonList(testId))
                .exec();
        // container stop
        list.forEach(container -> DockerClientService.removeContainer(dockerClient, container.getId()));
    }

    public List<Container> taskStatus(String testId) {
        DockerClient dockerClient = DockerClientService.connectDocker();
        List<Container> containerList = dockerClient.listContainersCmd()
                .withStatusFilter(Arrays.asList("created", "restarting", "running", "paused", "exited"))
                .withNameFilter(Collections.singletonList(testId))
                .exec();
        // 查询执行的状态
        return containerList;
    }

    public String logContainer(String testId) {
        LogUtil.info("Receive logs container request, test: {}", testId);
        DockerClient dockerClient = DockerClientService.connectDocker();

        // container filter
        List<Container> list = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withStatusFilter(Collections.singletonList("running"))
                .withNameFilter(Collections.singletonList(testId))
                .exec();

        StringBuilder sb = new StringBuilder();
        if (list.size() > 0) {
            try {
                dockerClient.logContainerCmd(list.get(0).getId())
                        .withFollowStream(true)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withTailAll()
                        .exec(new InvocationBuilder.AsyncResultCallback<Frame>() {
                            @Override
                            public void onNext(Frame item) {
                                sb.append(new String(item.getPayload()).trim()).append("\n");
                            }
                        }).awaitCompletion(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LogUtil.error(e);
            }
        }
        return sb.toString();
    }

    public byte[] downloadJtl(String reportId) {
        try {
            String jtlFileName = reportId + ".jtl";
            return IOUtils.toByteArray(new FileInputStream(ROOT_PATH + File.separator + jtlFileName));
        } catch (IOException e) {
            LogUtil.error(e);
        }
        return new byte[0];
    }

    public boolean deleteJtl(String reportId) {
        String jtlFileName = reportId + ".jtl";
        try {
            FileUtils.forceDelete(new File(ROOT_PATH + File.separator + jtlFileName));
            return true;
        } catch (IOException e) {
            LogUtil.error(e);
        }
        return false;
    }
}
