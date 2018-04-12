package com.parker.forex.utils;

import java.io.File;
import java.io.FilenameFilter;

public class FileRenamer {

	public static void main(String[] args) {
		String directory = "C:/Users/Shane/Desktop/PrimeFacesShowCase_files";
		File[] files = new File(directory).listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.endsWith("svg.xhtml");
			}
		});
		
		for (File file : files) {
			System.out.println("File renamed: " + file.getPath());
			//file.renameTo(new File(file.getPath().replace("svg.xhtml", "svg")));
		}
	}
}
