package io.github.okafke.aitcg.card.creation;

import io.github.okafke.aitcg.api.CardCreationRequest;
import io.github.okafke.aitcg.card.AiTCGCard;
import io.github.okafke.aitcg.card.AiTCGElement;
import io.github.okafke.aitcg.card.CreatureStats;
import io.github.okafke.aitcg.card.ImageService;
import io.github.okafke.aitcg.card.printing.FileService;
import io.github.okafke.aitcg.card.printing.PrintingService;
import io.github.okafke.aitcg.llm.Prompts;
import io.github.okafke.aitcg.llm.gpt.ChatGPT;
import io.github.okafke.aitcg.llm.gpt.GPTConversation;
import io.github.okafke.aitcg.llm.gpt.GPTMessage;
import io.github.okafke.aitcg.t2i.dalle.DallE3;
import io.github.okafke.aitcg.t2i.dalle.DallEResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_={@Autowired})
public class CardService {
    private final CardAlternationService alternationService;
    private final PrintingService printingService;
    private final ElementService elementService;
    private final ImageService imageService;
    private final FileService fileService;
    private final ChatGPT llm;
    private final DallE3 t2i;

    // TODO: make cross references to other cards in text
    // TODO: produce variant of card, evolution or something? Dall-E-2 can transform existing images
    // TODO: optimization, do not use a conversation but expect entire output at once in a certain format? Would save tokens!
    @Async
    public void createCard(CardCreationRequest request) throws IOException {
        UUID uuid = UUID.randomUUID();
        log.info("Creating card " + uuid + " for request " + request);
        // create Dall-E prompt
        GPTConversation conversation = llm.conversation();
        GPTMessage promptRequest = GPTMessage.user(Prompts.ONLY_OUTPUT + "Given a " + request.type()
                + " object with the attributes " + Prompts.list(request.attributes())
                + " in a fantastic setting, you are to design a prompt for Dall-E," +
                " in high detail and with a fitting background." +
                " Place great emphasis on the attributes and ensure that Dall-E does not include Text in the image..");
        conversation.add(promptRequest);
        GPTMessage dallEPrompt = llm.chat(conversation);
        log.info("Received Dall-E prompt " + dallEPrompt + " for attributes " + request.attributes());
        // use prompt to get image in parallel
        CompletableFuture<DallEResponse> image = t2i.sendRequest(dallEPrompt.content());

        // generate card name
        conversation.add(dallEPrompt);
        conversation.add(GPTMessage.user(Prompts.ONLY_OUTPUT + Prompts.NAME));
        conversation.max_tokens(10);
        GPTMessage name = llm.chat(conversation);
        log.info("Received name '" + name + "' for attributes " + request.attributes());

        // generate story for the card
        conversation.add(name);
        conversation.max_tokens(null);
        conversation.add(GPTMessage.user(Prompts.ONLY_OUTPUT + Prompts.NO_DALL_E + Prompts.RANDOM_AUTHOR));
        GPTMessage story = llm.chat(conversation);
        log.info("Received story " + story + " for attributes " + request.attributes());

        // request element for the card
        CompletableFuture<AiTCGElement> elementFuture = elementService.getElement(story, request);
        CompletableFuture<CardAlternationService.CardAlternation> secondCardFuture = alternationService.createEvolution(uuid, conversation);

        elementFuture.thenAccept(element -> log.info("Received element " + element + " for card " + uuid + ": " + name));
        image.thenAccept(dallEResponse -> {
            log.info("Received image for card " + uuid + ": " + name);
            elementFuture.thenAccept(element -> {
                CreatureStats stats = CreatureStats.roll();
                log.info("Building card " + uuid + " " + name + " with stats " + stats);
                AiTCGCard card = new AiTCGCard(uuid, name.content(), stats, element, null, story.content(), conversation, dallEResponse);
                try {
                    BufferedImage bufferedImage = imageService.createCard(card);
                    fileService.save(card, imageService.toPNG(bufferedImage));
                    secondCardFuture.thenAccept(cardAlternation -> {
                        log.info("Got Card alternation for " + uuid + ": " + name + ": " + cardAlternation);
                        try {
                            AiTCGCard secondCard = cardAlternation.awaitAiTCGCard(stats.increase(25), element);
                            BufferedImage secondCardImage = imageService.createCard(secondCard);
                            fileService.save(secondCard, imageService.toPNG(secondCardImage));
                            print(card, secondCard, bufferedImage, secondCardImage);
                        } catch (ExecutionException | InterruptedException | IOException e) {
                            log.error("Failed to create or print second card for cardAlternation " + cardAlternation, e);
                        }
                    });
                } catch (IOException e) {
                    log.error("Failed to create image for card " + card, e);
                }
            });
        });
        // join because tests end otherwise
        elementFuture.join();
        image.join();
        secondCardFuture.join();
    }

    private void print(AiTCGCard card, AiTCGCard secondCard, BufferedImage image, BufferedImage secondImage) {
        BufferedImage printImage = imageService.twoCards(image, secondImage);
        try {
            byte[] jpeg = imageService.toJpeg(printImage);
            fileService.savePrintingImage(card.uuid(), secondCard.uuid(), jpeg);
            printingService.print(card.uuid(), jpeg);
        } catch (IOException e) {
            log.error("Failed to print cards " + card + " " + secondCard, e);
        }
    }

}
