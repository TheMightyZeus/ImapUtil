package com.seiferware.imaptool;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.internet.MimeUtility;
import java.io.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ImapsManager {
	private static final Logger log = LoggerFactory.getLogger(ImapsManager.class);
	private Properties operation;
	private Store store;
	public void process() throws IOException {
		operation = new Properties();
		operation.load(getClass().getClassLoader().getResourceAsStream("operation.properties"));
		String task = operation.getProperty("task");
		String[] inf = operation.getProperty("address.usernames").split("[\n\r]+");
		for (String data : inf) {
			String[] cred = data.split(";");
			String un = cred[0];
			String pass = cred[1];
			try {
				connect(un, pass);
				switch (task) {
					case "delete":
						delete(un);
						break;
					case "report":
						report(un);
						break;
					case "byId":
						byId(un);
						break;
					case "move":
						move(un);
						break;
					case "count":
						count(un);
						break;
					case "check":
						break;
					default:
						log.warn("Unknown task!");
				}
				disconnect();
			} catch (Exception e) {
				log.error("Error handling account " + un, e);
				//e.printStackTrace();
			}
		}
	}
	private void count(String email) throws MessagingException {
		Folder folder = store.getFolder(operation.getProperty("task.count.folder"));
		if (folder.exists() && folder.getMessageCount() > 0) {
			log.info(email + ": " + folder.getMessageCount());
		}
	}
	private void delete(String email) throws MessagingException {
		Folder from = store.getFolder(operation.getProperty("task.delete.folder"));
		if (!from.exists()) {
			log.debug(email + ": from folder '" + from.getName() + "' doesn't exist!");
			return;
		}
		from.open(Folder.READ_WRITE);
		int total = from.getMessageCount();
		long time = System.currentTimeMillis();
		int done = 0;
		while (from.getMessageCount() > 0) {
			Message[] msgs = from.getMessages(1, Math.min(from.getMessageCount(), 100));
			from.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
			from.expunge();
			done += msgs.length;
			log.info(email + ": Processed " + done + " of " + total + " records in " + formatTime(System.currentTimeMillis() - time));
		}
	}
	private void move(String email) throws MessagingException {
		Folder from = store.getFolder(operation.getProperty("task.move.from"));
		Folder to = store.getFolder(operation.getProperty("task.move.to"));
		if (!from.exists()) {
			log.debug(email + ": from folder '" + from.getName() + "' doesn't exist!");
			return;
		}
		if (!to.exists()) {
			log.debug(email + ": to folder '" + to.getName() + "' doesn't exist!");
			return;
		}
		from.open(Folder.READ_WRITE);
		to.open(Folder.READ_WRITE);
		int total = from.getMessageCount();
		long time = System.currentTimeMillis();
		int done = 0;
		while (from.getMessageCount() > 0) {
			Message[] msgs = from.getMessages(1, Math.min(from.getMessageCount(), 100));
			from.copyMessages(msgs, to);
			from.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
			from.expunge();
			done += msgs.length;
			log.info(email + ": Processed " + done + " of " + total + " records in " + formatTime(System.currentTimeMillis() - time));
		}
		//System.out.println("Processed " + total + " records in " + formatTime(System.currentTimeMillis() - time));
	}
	private void byId(String email) throws IOException, MessagingException {
		log.info("Processing " + email);
		int count = Integer.parseInt(operation.getProperty("task.byId.operations"));
		int moved = 0;
		int deleted = 0;
		int ignored = 0;
		String[] names = new String[count + 1];
		Action[] acts = new Action[count + 1];
		Folder[] dest = new Folder[count + 1];
		for (int i = 0; i <= count; i++) {
			if (i > 0) {
				names[i] = operation.getProperty("task.byId.op." + i + ".name");
			}
			acts[i] = Action.valueOf(operation.getProperty("task.byId.op." + (i == 0 ? "default" : i) + ".action").toUpperCase());
			if (acts[i] == Action.MOVE) {
				dest[i] = store.getFolder(operation.getProperty("task.byId.op." + (i == 0 ? "default" : i) + ".folder"));
				if (!dest[i].exists()) {
					dest[i].create(Folder.HOLDS_MESSAGES);
				}
				dest[i].open(Folder.READ_WRITE);
			}
		}
		int idColumn = Integer.parseInt(operation.getProperty("task.byId.idColumn"));
		int actionColumn = Integer.parseInt(operation.getProperty("task.byId.actionColumn"));
		Map<String, String> ids = new HashMap<>();
		boolean[] needSkip = {Boolean.parseBoolean(operation.getProperty("task.byId.headers"))};
		try (FileReader fr = new FileReader(operation.getProperty("task.byId.input").replace("%", email))) {
			CSVParser csv = CSVFormat.EXCEL.parse(fr);
			List<CSVRecord> records = csv.getRecords();
			records.forEach(r -> {
				if (needSkip[0]) {
					needSkip[0] = false;
					return;
				}
				String id = r.get(idColumn);
				String act = r.get(actionColumn);
				if (ids.containsKey(id)) {
					if (ids.get(id).equals(act)) {
						log.debug("Duplicate record: " + id + ", " + act);
					} else {
						throw new IllegalStateException("Conflicting record: " + id + " tried to merge " + act + " and " + ids.get(id));
					}
				}
				ids.put(id, act);
			});
		}
		Folder folder = store.getFolder(operation.getProperty("task.byId.folder"));
		folder.open(Folder.READ_WRITE);
		int total = folder.getMessageCount();
		long time = System.currentTimeMillis();
		for (int i = 1; i <= total; i++) {
			try {
				Message msg = folder.getMessage(i);
				String id = tryGetFirst(msg.getHeader("Message-ID"));
				int op = 0;
				if (ids.containsKey(id)) {
					String name = ids.get(id);
					for (int n = 1; n <= count; n++) {
						if (name.equalsIgnoreCase(names[n])) {
							op = n;
							break;
						}
					}
				}
				switch (acts[op]) {
					case DELETE:
						msg.setFlag(Flags.Flag.DELETED, true);
						deleted++;
						break;
					case MOVE:
						folder.copyMessages(new Message[]{msg}, dest[op]);
						folder.setFlags(new Message[]{msg}, new Flags(Flags.Flag.DELETED), true);
						moved++;
						break;
					default:
						ignored++;
				}
			} catch (Exception e) {
				log.error("Error on message " + i);
				e.printStackTrace();
			}
			if (i % 100 == 0 || (i % 10 == 0 && i < 100)) {
				log.info("Processed " + i + " out of " + total + " records in " + formatTime(System.currentTimeMillis() - time));
				log.info("Kept: " + ignored + ", Deleted: " + deleted + ", Moved: " + moved);
			}
		}
		folder.expunge();
		log.info("Processed " + total + " records in " + formatTime(System.currentTimeMillis() - time));
		log.info("Kept: " + ignored + ", Deleted: " + deleted + ", Moved: " + moved);
	}
	private void disconnect() throws MessagingException {
		store.close();
	}
	private void connect(String username, String password) throws IOException, MessagingException {
		Properties sessionProps = new Properties();
		sessionProps.load(getClass().getClassLoader().getResourceAsStream("connection.properties"));
		Session session = Session.getInstance(sessionProps);
		store = session.getStore("imaps");
		
		String host = operation.getProperty("address.hostname");
		if (host.contains(":")) {
			String[] hostport = host.split(host, ':');
			store.connect(hostport[0], Integer.parseInt(hostport[1]), username, password);
		}
		//System.out.println("Connected");
	}
	private void report(String email) throws MessagingException, IOException {
		log.info("Processing " + email);
		Folder folder = store.getFolder(operation.getProperty("task.report.folder"));
		folder.open(Folder.READ_ONLY);
		long time = System.currentTimeMillis();
		
		try (FileWriter fw = new FileWriter(operation.getProperty("task.report.output").replace("%", email)); BufferedWriter out = new BufferedWriter(fw)) {
			CSVPrinter csv = CSVFormat.EXCEL.withHeader("Message ID", "Date", "From", "To", "Subject").print(out);
			DateTimeFormatter dtf = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.US).withZone(ZoneId.systemDefault());
			Message[] msgs = folder.getMessages();
			for (int i = 0; i < msgs.length; i++) {
				Message msg = msgs[i];
				try {
					String from = "";
					Address[] add1 = msg.getFrom();
					if (add1 != null && add1.length > 0) {
						from = tryDecode(add1[0].toString(), true);
					} else {
						Address[] add2 = msg.getReplyTo();
						if (add2 != null && add2.length > 0) {
							from = tryDecode(add2[0].toString(), true);
						}
					}
					Address[] origTo = msg.getRecipients(Message.RecipientType.TO);
					String to = "";
					if (origTo != null) {
						to = Stream.of(origTo).map(Address::toString).map(a -> tryDecode(a, true)).collect(Collectors.joining(", "));
					}
					String subject = tryDecode(msg.getSubject(), false);
					String msgId = tryGetFirst(msg.getHeader("Message-ID"));
					String date = dtf.format(msg.getSentDate().toInstant());
					
					csv.printRecord(msgId, date, from, to, subject);
					if (i % 100 == 0) {
						log.info("Processed " + i + " out of " + msgs.length + " records in " + formatTime(System.currentTimeMillis() - time));
					}
				} catch (MessageRemovedException ignored) {
				}
			}
		}
		time = System.currentTimeMillis() - time;
		log.info("Total time: " + formatTime(time));
		store.close();
	}
	private String formatTime(long time) {
		long seconds = time % 60000;
		long minutes = (time / 60000) % 60;
		long hours = time / 3600000;
		String result = seconds / 1000 + "." + ((seconds / 100) % 10) + "s";
		if (minutes > 0 || hours > 0) {
			result = minutes + "m " + result;
		}
		if (hours > 0) {
			result = hours + "h " + result;
		}
		return result;
	}
	private String tryGetFirst(String[] header) {
		if (header == null || header.length == 0) {
			return null;
		}
		return header[0];
	}
	private void extractContent(Part data) throws IOException, MessagingException {
		log.debug("Reading " + data.getContentType() + ": " + data.getSize());
		Object content = data.getContent();
		if (content == null) {
			return;
		}
		if (content instanceof Multipart) {
			Multipart multi = (Multipart) content;
			for (int i = 0; i < multi.getCount(); i++) {
				BodyPart part = multi.getBodyPart(i);
				extractContent(part);
			}
		} else if (!(content instanceof String)) {
			if (!(content instanceof InputStream)) {
				log.debug("Converting to input stream");
				content = data.getInputStream();
			}
			log.debug("Reading input stream");
			byte[] bytes = copyBytes((InputStream) content);
		}
	}
	private byte[] copyBytes(InputStream input) throws IOException {
		try (final ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[1024 * 1024];
			long count = 0;
			int n;
			while (-1 != (n = readBytes(input, buffer))) {
				count += n;
				log.debug("Read " + count + " bytes");
				output.write(buffer, 0, n);
			}
			return output.toByteArray();
		}
	}
	private int readBytes(InputStream input, byte[] buffer) {
		int c;
		int i = 0;
		int len = buffer.length;
		try {
			for (; i < len; i++) {
				c = input.read();
				if (c == -1) {
					break;
				}
				buffer[i] = (byte) c;
			}
		} catch (IOException ignored) {
		}
		return i == 0 ? -1 : i;
	}
	private String tryDecode(String input, boolean needQuotes) {
		if (input == null) {
			return "";
		}
		try {
			int match = -1;
			while (input.contains("=?") && input.indexOf("=?") != match) {
				match = input.indexOf("=?");
				int end = input.indexOf("?=", match + 2) + 2;
				String decoded = MimeUtility.decodeText(input.substring(match, end));
				if (needQuotes && decoded.length() < 2) {
					decoded = "\"" + decoded + "\"";
				} else if (needQuotes && (!decoded.substring(0, 1).equals("\"") && (match == 0 || !input.substring(match - 1, match).equals("\"")))) {
					decoded = "\"" + decoded + "\"";
				}
				input = input.substring(0, match) + decoded + input.substring(end);
			}
			return input;
		} catch (Exception e) {
			log.error("Error decoding: " + input);
			return input;
		}
	}
	private enum Action {
		IGNORE, DELETE, MOVE
	}
}
