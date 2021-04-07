package org.nlpcn.dubbotest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.File;

/**
 * @author : qihang.liu
 * @date 2021-04-07
 */
@Component
public class MavenConfig {
    private static final Logger LOG = LoggerFactory.getLogger(MavenConfig.class);

    @Value("${mavenLocalRepository:}")
    private String localRepository;

    @PostConstruct
    public void init() {
        if (StringUtils.isEmpty(localRepository)) {
            localRepository = System.getenv("HOME") + File.separator + ".m2" + File.separator + "repository";
        }

        LOG.info("Maven local repository is {}", localRepository);
    }

    public String getLocalRepository() {
        return localRepository;
    }

    public void setLocalRepository(String localRepository) {
        this.localRepository = localRepository;
    }
}
