package org.e2k;

import javax.swing.JOptionPane;

public class CCIR493 extends FSK {
	
	private int state=0;
	private double samplesPerSymbol;
	private Rivet theApp;
	public long sampleCount=0;
	private long symbolCounter=0;
	public StringBuffer lineBuffer=new StringBuffer();
	private int highTone;
	private int lowTone;
	private int messageState;
	private int totalErrorCount=0;
	private int characterCount=0;
	private int totalCharacterCount=0;
	private final int MAXCHARLENGTH=80;
	private int highBin;
	private int lowBin;
	private double adjBuffer[]=new double[5];
	private int adjCounter=0;
	private int buffer10=0;
	private int dx;
	private int rx;
	
	public CCIR493 (Rivet tapp)	{
		theApp=tapp;
	}
	
	// The main decode routine
	public String[] decode (CircularDataBuffer circBuf,WaveData waveData)	{
		String outLines[]=new String[3];
			
		if (state==0)	{
			// Check the sample rate
			if (waveData.getSampleRate()!=8000.0)	{
				state=-1;
				JOptionPane.showMessageDialog(null,"WAV files containing\nCCIR493-4 recordings must have\nbeen recorded at a sample rate\nof 8 KHz.","Rivet", JOptionPane.INFORMATION_MESSAGE);
				return null;
			}
			// Check this is a mono recording
			if (waveData.getChannels()!=1)	{
				state=-1;
				JOptionPane.showMessageDialog(null,"Rivet can only process\nmono WAV files.","Rivet", JOptionPane.INFORMATION_MESSAGE);
				return null;
			}
			// sampleCount must start negative to account for the buffer gradually filling
			sampleCount=0-circBuf.retMax();
			symbolCounter=0;
			samplesPerSymbol=samplesPerSymbol(100.0,waveData.getSampleRate());
			state=1;
			lineBuffer.delete(0,lineBuffer.length());
			messageState=0;
			theApp.setStatusLabel("Sync Hunt");
			return null;
		}
		
		
		// Look for a 100 baud alternating sequence
		if (state==1)	{
			sampleCount++;
			if (sampleCount<0) return null;
			// Look for a 100 baud alternating sync sequence
			if (detectSync(circBuf,waveData)==true)	{
				state=2;
				totalErrorCount=0;
				totalCharacterCount=0;
				characterCount=0;
				rx=0;
				dx=0;
				buffer10=0;
				return outLines;
			}
		}		
		
		if (state==2)	{
			if (symbolCounter>=(long)samplesPerSymbol)	{		
				// Demodulate a single bit
				boolean bit=getSymbolFreqBin(circBuf,waveData,0);
				
				if (theApp.isDebug()==true)	{
					if (bit==true) lineBuffer.append("1");
					else lineBuffer.append("0");
					characterCount++;
					if (characterCount==MAXCHARLENGTH)	{
						outLines[0]=lineBuffer.toString();
						characterCount=0;
						lineBuffer.delete(0,lineBuffer.length());
					}
				}
				else outLines=handleTraffic(bit);
			}
		}
		
		
		sampleCount++;
		symbolCounter++;
		return outLines;
		}
	

	
	public void setState(int state) {
		this.state = state;
	}

	public int getState() {
		return state;
	}
	
	// See if the buffer holds a alternating sequence
	private boolean detectSync(CircularDataBuffer circBuf,WaveData waveData)	{
		int pos=0,b0,b1;
		int f0=getSymbolFreq(circBuf,waveData,pos);
		b0=getFreqBin();
		// Check this first tone isn't just noise the highest bin must make up 10% of the total
		if (getPercentageOfTotal()<10.0) return false;
		pos=(int)samplesPerSymbol*1;
		int f1=getSymbolFreq(circBuf,waveData,pos);
		b1=getFreqBin();
		if (f0==f1) return false;
		if (f0>f1)	{
			highTone=f0;
			highBin=b0;
			lowTone=f1;
			lowBin=b1;
			}
			else	{
			highTone=f1;
			highBin=b1;
			lowTone=f0;
			lowBin=b0;
			}
		int shift=highTone-lowTone;
		// The shift for CCIR493-4 should be should be 170 Hz
		if ((shift>=150)&&(shift<180)) return true;
		else return false;
	}	
	
	// Get the frequency at a certain symbol
	private int getSymbolFreq (CircularDataBuffer circBuf,WaveData waveData,int start)	{
		int fr=doCCIR493_160FFT(circBuf,waveData,start);
		return fr;
	}	
	
	// Return the symbol frequency given the bins that hold the possible tones
	private boolean getSymbolFreqBin (CircularDataBuffer circBuf,WaveData waveData,int start)	{
		boolean bit;
		double early[]=do160FFTHalfSymbolBinRequest(circBuf,start,lowBin,highBin);
		start=start+((int)samplesPerSymbol/2);
		double late[]=do160FFTHalfSymbolBinRequest(circBuf,start,lowBin,highBin);
		
		addToAdjBuffer(early[0]-late[0]);
		symbolCounter=adjAdjust();

		double lowTotal=early[0]+late[0];
		double highTotal=early[1]+late[1];
		if (theApp.isInvertSignal()==false)	{
			if (lowTotal>highTotal) bit=true;
			else bit=false;
		}
		else	{
			if (lowTotal>highTotal) bit=true;
			else bit=false;
		}
		return bit;
	}
	
	// Add a comparator output to a circular buffer of values
	private void addToAdjBuffer (double in)	{
		adjBuffer[adjCounter]=in;
		adjCounter++;
		if (adjCounter==adjBuffer.length) adjCounter=0;
	}
	
	// Return the average of the circular buffer
	private double adjAverage()	{
		int a;
		double total=0.0;
		for (a=0;a<adjBuffer.length;a++)	{
			total=total+adjBuffer[a];
		}
		return (total/adjBuffer.length);
	}
	
	// Get the average value and return an adjustment value
	private int adjAdjust()	{
		double av=adjAverage();
		if (Math.abs(av)<100) return 0;
		else if (av<0.0) return 1;
		else return -1;
	}		
	
	// The main function for handling incoming bits
	private String[] handleTraffic (boolean b)	{
		String outLines[]=new String[3];
		addTo10BitBuffer(b);
		// Hunt for dx and rx characters which make up the phasing sequence
		if (messageState==0)	{
			int c=ret10BitCode(buffer10);
			if (c==125) dx++;
			else if ((c<=111)&&(c>=104)) rx++;
			// Is phasing complete ?
			if (((dx==2)&&(rx==1))||((dx==1)&&(rx==2))) messageState=1;
		}
		// Phasing complete
		else if (messageState==1)	{
			
			
			if (b==true) lineBuffer.append("1");
			else lineBuffer.append("0");
			characterCount++;
			if (characterCount==MAXCHARLENGTH)	{
				outLines[0]=lineBuffer.toString();
				characterCount=0;
				lineBuffer.delete(0,lineBuffer.length());
			}
			
			
		}
		
		
		return outLines;
	}
	
	// Add a bit to the 10 bit buffer
	private void addTo10BitBuffer (boolean b)	{
		buffer10=buffer10<<1;
		buffer10=buffer10&0x3FF;
		if (b==true) buffer10++;
	}
	
	
	private int ret10BitCode (int in)	{
		int o=0;
		if ((in&512)>0) o=1;
		if ((in&256)>0) o=o+2;
		if ((in&128)>0) o=o+4;
		if ((in&64)>0) o=o+8;
		if ((in&32)>0) o=o+16;
		if ((in&16)>0) o=o+32;
		if ((in&8)>0) o=o+64;
		// Bits 4,2 and 1 are check bits
		return o;
	}

}
