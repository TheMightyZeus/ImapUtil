package com.seiferware.imaptool;

import java.io.IOException;

public class Application {
	public static void main(String[] args) {
		try {
			new ImapsManager().process();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
