package com.e.demo.server;

import com.e.demo.config.RabbitConfig;
import com.e.demo.dto.PatchInferenceEvent;
import com.e.demo.dto.WsiUploadedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueuePublisher {
  private final RabbitTemplate rabbitTemplate;

  public void publishWsiUploaded(WsiUploadedEvent e) {
    rabbitTemplate.convertAndSend(RabbitConfig.Q_WSI_UPLOADED, e);
  }

  public void publishPatchInference(PatchInferenceEvent e) {
    rabbitTemplate.convertAndSend(RabbitConfig.Q_PATCHES_INFERENCE, e);
  }
}
