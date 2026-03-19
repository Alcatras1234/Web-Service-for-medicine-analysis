package com.e.demo.server;

import com.e.demo.dto.WsiUploadedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueuePublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishWsiUploaded(WsiUploadedEvent event) {
        rabbitTemplate.convertAndSend("wsi.uploaded", event);
    }
}
