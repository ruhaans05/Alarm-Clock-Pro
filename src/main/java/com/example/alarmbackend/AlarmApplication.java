package com.example.alarmbackend;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.cdimascio.dotenv.Dotenv;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SpringBootApplication
public class AlarmApplication {

	public static void main(String[] args) {
		// Load variables from .env file
		Dotenv dotenv = Dotenv.load();
		// Set them as system properties so Spring can access them
		dotenv.entries().forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));
		
		SpringApplication.run(AlarmApplication.class, args);
	}

	// === JPA ENTITY ===
	@Entity
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	static class Alarm {
		@Id
		@GeneratedValue
		private Long id;
		private String name;
		private String time; // "HH:mm" format
		
		@ElementCollection(fetch = FetchType.EAGER)
		private List<Integer> daysOfWeek; // 0=Sun, 1=Mon, ...
		
		private boolean isActive = true;
		private String endDate; // "YYYY-MM-DD"
	}

	// === JPA REPOSITORY ===
	@Repository
	interface AlarmRepository extends JpaRepository<Alarm, Long> {
		List<Alarm> findByTime(String time);
	}

	// === DTOs (Data Transfer Objects) ===
	@Data
	static class NaturalLanguageCommandRequest {
		private String command;
	}
	
	@Data
	static class CommandResponse {
		private String message;
		private Object data;
	}

	// === AI SERVICE FOR OPENAI ===
	@Service
	static class OpenAIService {
		// Inject the API key from application.properties
		@Value("${openai.api.key}")
		private String openAIApiKey;
		
		private final String openAIUrl = "https://api.openai.com/v1/chat/completions";
		private final RestTemplate restTemplate = new RestTemplate();
		private final ObjectMapper objectMapper = new ObjectMapper();

		public ParsedCommand parseCommand(String command) {
			String prompt = buildPrompt(command);
			
			String requestBody = """
				{
				  "model": "gpt-4-turbo",
				  "messages": [
					{
					  "role": "system",
					  "content": "%s"
					},
					{
					  "role": "user",
					  "content": "%s"
					}
				  ],
				  "response_format": { "type": "json_object" }
				}
			""".formatted(prompt, command);

			try {
				org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
				headers.set("Authorization", "Bearer " + openAIApiKey);
				headers.set("Content-Type", "application/json");

				org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(requestBody, headers);
				
				ResponseEntity<String> response = restTemplate.postForEntity(openAIUrl, entity, String.class);
				
				OpenAIResponse openAIResponse = objectMapper.readValue(response.getBody(), OpenAIResponse.class);
				String jsonContent = openAIResponse.getChoices().get(0).getMessage().getContent();

				return objectMapper.readValue(jsonContent, ParsedCommand.class);

			} catch (Exception e) {
				e.printStackTrace();
				ParsedCommand errorCommand = new ParsedCommand();
				errorCommand.setAction("ERROR");
				errorCommand.setResponseMessage("Sorry, I couldn't process that. There might be an issue with the AI service.");
				return errorCommand;
			}
		}

		private String buildPrompt(String command) {
			String currentDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
			DayOfWeek currentDay = LocalDate.now().getDayOfWeek();
			
			return """
				You are an intelligent assistant for an alarm clock application. Your task is to parse a user's natural language command and convert it into a structured JSON object.
				The current date is %s, which is a %s.
				Analyze the user's request and determine the action, time, days, and recurrence rules.

				GUIDELINES:
				- The `action` must be one of: `CREATE`, `UPDATE`, `DELETE`, or `INCOMPLETE`.
				- `times` should be an array of one or more times in "HH:mm" (24-hour) format. Convert all times like "9pm" to "21:00".
				- `daysOfWeek` should be an array of integers where Sunday=0, Monday=1, ..., Saturday=6. If no day is mentioned, but "tomorrow" is, calculate the correct day. If no day is mentioned at all, assume it's for today.
				- `endDate` should be a date in "YYYY-MM-DD" format if a duration is specified (e.g., "for the next 2 weeks", "for 5 months"). Calculate this date based on the current date.
				- For `UPDATE` or `DELETE` actions, you MUST identify the original alarm's details in `originalTime` and `originalDaysOfWeek` to help the system find it.
				- `alarmName` can be inferred from the command if provided, otherwise default to "Alarm".
				- Provide a user-friendly `responseMessage` confirming the action or asking for clarification.
				- If the command is ambiguous or lacks a specific time, set action to `INCOMPLETE`.
				- "Every day" means all 7 days of the week. "Weekdays" means Monday to Friday. "Weekends" means Saturday and Sunday.

				JSON STRUCTURE:
				{
				  "action": "...",
				  "alarmName": "...",
				  "times": ["HH:mm"],
				  "daysOfWeek": [int],
				  "endDate": "YYYY-MM-DD" | null,
				  "originalTime": "HH:mm" | null,
				  "originalDaysOfWeek": [int] | null,
				  "responseMessage": "..."
				}

				EXAMPLES:
				User: "set an alarm at 9 pm on saturday for the next 2 weeks"
				JSON: { "action": "CREATE", "alarmName": "Alarm", "times": ["21:00"], "daysOfWeek": [6], "endDate": "%s", "originalTime": null, "originalDaysOfWeek": null, "responseMessage": "OK, I've set an alarm for 9:00 PM on Saturdays for the next 2 weeks."}
				
				User: "make an alarm at 8am tomorrow"
				JSON: { "action": "CREATE", "alarmName": "Alarm", "times": ["08:00"], "daysOfWeek": [%d], "endDate": null, "originalTime": null, "originalDaysOfWeek": null, "responseMessage": "OK, I've set an alarm for 8:00 AM tomorrow."}
				
				User: "change the alarm i have at 8am on fridays to 9pm on saturdays for the next 5 months"
				JSON: { "action": "UPDATE", "alarmName": "Alarm", "times": ["21:00"], "daysOfWeek": [6], "endDate": "%s", "originalTime": "08:00", "originalDaysOfWeek": [5], "responseMessage": "OK, I've changed your 8:00 AM Friday alarm to 9:00 PM on Saturdays for the next 5 months."}

				User: "delete my 8am weekday alarm"
				JSON: { "action": "DELETE", "alarmName": null, "times": null, "daysOfWeek": null, "endDate": null, "originalTime": "08:00", "originalDaysOfWeek": [1,2,3,4,5], "responseMessage": "OK, I've deleted your 8:00 AM weekday alarm."}
				
			""".formatted(
				currentDate, 
				currentDay.toString(),
				LocalDate.now().plusWeeks(2).format(DateTimeFormatter.ISO_LOCAL_DATE),
				LocalDate.now().plusDays(1).getDayOfWeek().getValue() % 7,
				LocalDate.now().plusMonths(5).format(DateTimeFormatter.ISO_LOCAL_DATE)
			);
		}
	}

	// === DTOs for OpenAI JSON parsing ===
	@Data
	static class OpenAIResponse {
		private List<Choice> choices;
	}
	@Data
	static class Choice {
		private Message message;
	}
	@Data
	static class Message {
		private String role;
		private String content;
	}
	@Data
	static class ParsedCommand {
		private String action;
		private String alarmName;
		private List<String> times;
		private List<Integer> daysOfWeek;
		private String endDate;
		private String originalTime;
		private List<Integer> originalDaysOfWeek;
		private String responseMessage;
	}
	
	// === MAIN ALARM SERVICE (Business Logic) ===
	@Service
	static class AlarmService {

		@Autowired
		private AlarmRepository alarmRepository;

		@Autowired
		private OpenAIService openAIService;

		public CommandResponse processNaturalLanguageCommand(String command) {
			ParsedCommand parsedCommand = openAIService.parseCommand(command);
			CommandResponse response = new CommandResponse();

			switch (parsedCommand.getAction().toUpperCase()) {
				case "CREATE":
					for (String time : parsedCommand.getTimes()) {
						Alarm newAlarm = new Alarm();
						newAlarm.setName(parsedCommand.getAlarmName());
						newAlarm.setTime(time);
						newAlarm.setDaysOfWeek(parsedCommand.getDaysOfWeek());
						newAlarm.setEndDate(parsedCommand.getEndDate());
						alarmRepository.save(newAlarm);
					}
					response.setMessage(parsedCommand.getResponseMessage());
					response.setData(alarmRepository.findAll());
					break;

				case "UPDATE":
					List<Alarm> alarmsToUpdate = findAlarms(parsedCommand.getOriginalTime(), parsedCommand.getOriginalDaysOfWeek());
					if (alarmsToUpdate.isEmpty()) {
						response.setMessage("I couldn't find an alarm for " + parsedCommand.getOriginalTime() + " to update.");
					} else {
						alarmsToUpdate.forEach(alarmRepository::delete);

						Alarm updatedAlarm = new Alarm();
						updatedAlarm.setName(parsedCommand.getAlarmName());
						updatedAlarm.setTime(parsedCommand.getTimes().get(0));
						updatedAlarm.setDaysOfWeek(parsedCommand.getDaysOfWeek());
						updatedAlarm.setEndDate(parsedCommand.getEndDate());
						alarmRepository.save(updatedAlarm);
						
						response.setMessage(parsedCommand.getResponseMessage());
					}
					response.setData(alarmRepository.findAll());
					break;

				case "DELETE":
					List<Alarm> alarmsToDelete = findAlarms(parsedCommand.getOriginalTime(), parsedCommand.getOriginalDaysOfWeek());
					if (alarmsToDelete.isEmpty()) {
						response.setMessage("I couldn't find an alarm for " + parsedCommand.getOriginalTime() + " to delete.");
					} else {
						alarmsToDelete.forEach(alarmRepository::delete);
						response.setMessage(parsedCommand.getResponseMessage());
					}
					response.setData(alarmRepository.findAll());
					break;

				case "INCOMPLETE":
				case "ERROR":
				default:
					response.setMessage(parsedCommand.getResponseMessage());
					response.setData(alarmRepository.findAll());
					break;
			}
			return response;
		}

		private List<Alarm> findAlarms(String time, List<Integer> days) {
			List<Alarm> foundAlarms = new ArrayList<>();
			if (time == null || days == null) return foundAlarms;

			List<Alarm> alarmsForTime = alarmRepository.findByTime(time);
			for (Alarm alarm : alarmsForTime) {
				if (new ArrayList<>(alarm.getDaysOfWeek()).equals(new ArrayList<>(days))) {
					foundAlarms.add(alarm);
				}
			}
			return foundAlarms;
		}
	}
	
	// === REST CONTROLLER ===
	@RestController
	@RequestMapping("/api/alarms")
	public static class AlarmController {

		@Autowired
		private AlarmService alarmService;
		@Autowired
		private AlarmRepository alarmRepository;

		@PostMapping("/process-command")
		public ResponseEntity<CommandResponse> processCommand(@RequestBody NaturalLanguageCommandRequest request) {
			CommandResponse response = alarmService.processNaturalLanguageCommand(request.getCommand());
			return ResponseEntity.ok(response);
		}

		@GetMapping
		public List<Alarm> getAllAlarms() {
			return alarmRepository.findAll();
		}

		@PostMapping
		public Alarm createAlarm(@RequestBody Alarm alarm) {
			return alarmRepository.save(alarm);
		}

		@PutMapping("/{id}")
		public ResponseEntity<Alarm> updateAlarm(@PathVariable Long id, @RequestBody Alarm alarmDetails) {
			return alarmRepository.findById(id)
				.map(alarm -> {
					alarm.setName(alarmDetails.getName());
					alarm.setTime(alarmDetails.getTime());
					alarm.setDaysOfWeek(alarmDetails.getDaysOfWeek());
					alarm.setEndDate(alarmDetails.getEndDate());
					alarm.setActive(alarmDetails.isActive());
					Alarm updatedAlarm = alarmRepository.save(alarm);
					return ResponseEntity.ok(updatedAlarm);
				}).orElse(ResponseEntity.notFound().build());
		}

		@DeleteMapping("/{id}")
		public ResponseEntity<Void> deleteAlarm(@PathVariable Long id) {
			return alarmRepository.findById(id)
				.map(alarm -> {
					alarmRepository.delete(alarm);
					return ResponseEntity.ok().<Void>build();
				}).orElse(ResponseEntity.notFound().build());
		}
		
		@PatchMapping("/{id}/toggle")
        public ResponseEntity<Alarm> toggleAlarm(@PathVariable Long id) {
            Optional<Alarm> alarmOptional = alarmRepository.findById(id);
            if (alarmOptional.isPresent()) {
                Alarm alarm = alarmOptional.get();
                alarm.setActive(!alarm.isActive());
                alarmRepository.save(alarm);
                return ResponseEntity.ok(alarm);
            }
            return ResponseEntity.notFound().build();
        }
	}

	// === WEB CONFIGURATION FOR CORS ===
	@Configuration
	static class WebConfig implements WebMvcConfigurer {
		@Override
		public void addCorsMappings(CorsRegistry registry) {
			registry.addMapping("/**")
				.allowedOrigins("*")
				.allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
				.allowedHeaders("*");
		}
	}
}

